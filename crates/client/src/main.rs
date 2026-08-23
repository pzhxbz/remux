use std::{
    io::{self, Write as _},
    time::Duration,
};

use anyhow::{Context, Result};
use clap::{Args as ClapArgs, Parser, Subcommand, ValueEnum};
use crossterm::terminal::{disable_raw_mode, enable_raw_mode, size};
use futures_util::{
    SinkExt, StreamExt,
    stream::{SplitSink, SplitStream},
};
use remux_protocol::{
    AgentPayload, ClientPayload, Command, CommandResult, MachineInfo, PROTOCOL_VERSION, SizePolicy,
    WireMessage, relay_connector, terminal_bytes_to_text, terminal_text_to_bytes, wire_from_text,
    wire_to_text,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::MissedTickBehavior;
use tokio_tungstenite::{
    MaybeTlsStream, WebSocketStream, connect_async_tls_with_config, tungstenite::Message,
};
use uuid::Uuid;

type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;
type SocketSink = SplitSink<Socket, Message>;
type SocketSource = SplitStream<Socket>;

#[derive(Debug, Parser)]
#[command(
    name = "remux-client",
    version,
    about = "RemoteMux reference CLI client"
)]
struct Cli {
    /// Relay base URL (wss://; ws:// only in debug builds).
    #[arg(long, global = true)]
    relay: Option<String>,

    #[arg(
        long,
        env = "REMUX_CLIENT_TOKEN",
        global = true,
        hide_env_values = true
    )]
    token: Option<String>,

    #[arg(long, global = true)]
    client_id: Option<Uuid>,

    #[arg(long, global = true, default_value = "rust-client")]
    client_name: String,

    #[arg(long, global = true)]
    json: bool,

    #[command(subcommand)]
    command: ClientCommand,
}

#[derive(Debug, Clone, ClapArgs)]
struct Target {
    /// Machine id or exact name, resolved against the relay's online machines.
    #[arg(long)]
    machine: String,
}

#[derive(Debug, Subcommand)]
enum ClientCommand {
    /// List machines currently connected to the relay.
    Machines,

    /// List tmux sessions on one online machine.
    Sessions(Target),

    /// List windows in a tmux session.
    Windows {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        session_id: String,
    },

    /// List panes in a tmux window.
    Panes {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        window_id: String,
    },

    /// Create a detached tmux session running the user's default shell.
    CreateSession {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        name: String,
        #[arg(long)]
        cwd: Option<String>,
    },

    RenameSession {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        session_id: String,
        #[arg(long)]
        name: String,
    },

    /// Permanently terminate a tmux session and its programs.
    KillSession {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        session_id: String,
        #[arg(long, help = "Confirm the destructive operation")]
        confirm: bool,
    },

    CreateWindow {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        session_id: String,
        #[arg(long)]
        name: Option<String>,
        #[arg(long)]
        cwd: Option<String>,
    },

    RenameWindow {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        window_id: String,
        #[arg(long)]
        name: String,
    },

    /// Permanently terminate a tmux window and its programs.
    KillWindow {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        window_id: String,
        #[arg(long, help = "Confirm the destructive operation")]
        confirm: bool,
    },

    /// Attach this terminal to a remote tmux session. Detach with Ctrl-\\, then d.
    Attach {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        session_id: String,
        #[arg(long, value_enum, default_value_t = SizePolicyArg::Auto)]
        size_policy: SizePolicyArg,
    },
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum SizePolicyArg {
    Auto,
    PreserveExisting,
    TakeControl,
}

impl From<SizePolicyArg> for SizePolicy {
    fn from(value: SizePolicyArg) -> Self {
        match value {
            SizePolicyArg::Auto => Self::Auto,
            SizePolicyArg::PreserveExisting => Self::PreserveExisting,
            SizePolicyArg::TakeControl => Self::TakeControl,
        }
    }
}

struct ClientConnection {
    machine_id: Uuid,
    sink: SocketSink,
    source: SocketSource,
}

#[tokio::main]
async fn main() -> Result<()> {
    // tokio-tungstenite pulls in rustls without a default CryptoProvider, so
    // select one explicitly before any wss:// connection is attempted.
    rustls::crypto::ring::default_provider()
        .install_default()
        .ok();

    let cli = Cli::parse();
    let token = cli
        .token
        .as_deref()
        .context("--token or REMUX_CLIENT_TOKEN is required")?
        .to_owned();
    anyhow::ensure!(
        token.len() >= 16,
        "relay client token must be at least 16 characters"
    );
    let client_id = cli.client_id.unwrap_or_else(Uuid::new_v4);

    match cli.command {
        ClientCommand::Machines => {
            let relay = cli.relay.context("--relay is required for machines")?;
            let machines = list_machines(&relay, &token, client_id, &cli.client_name).await?;
            print_machines(&machines, cli.json)?;
        }
        command => {
            let target = command_target(&command).expect("machine-targeted command");
            let relay = cli.relay.context("--relay is required")?;
            let mut connection = ClientConnection::connect(
                &relay,
                &token,
                client_id,
                &cli.client_name,
                &target.machine,
            )
            .await?;

            match command {
                ClientCommand::Sessions(_) => {
                    print_result(connection.request(Command::ListSessions).await?, cli.json)?;
                }
                ClientCommand::Windows { session_id, .. } => {
                    print_result(
                        connection
                            .request(Command::ListWindows { session_id })
                            .await?,
                        cli.json,
                    )?;
                }
                ClientCommand::Panes { window_id, .. } => {
                    print_result(
                        connection.request(Command::ListPanes { window_id }).await?,
                        cli.json,
                    )?;
                }
                ClientCommand::CreateSession { name, cwd, .. } => {
                    print_result(
                        connection
                            .request(Command::CreateSession { name, cwd })
                            .await?,
                        cli.json,
                    )?;
                }
                ClientCommand::RenameSession {
                    session_id, name, ..
                } => {
                    print_result(
                        connection
                            .request(Command::RenameSession {
                                session_id,
                                new_name: name,
                            })
                            .await?,
                        cli.json,
                    )?;
                }
                ClientCommand::KillSession {
                    session_id,
                    confirm,
                    ..
                } => {
                    require_confirmation(confirm, "kill-session")?;
                    print_result(
                        connection
                            .request(Command::KillSession { session_id })
                            .await?,
                        cli.json,
                    )?;
                }
                ClientCommand::CreateWindow {
                    session_id,
                    name,
                    cwd,
                    ..
                } => {
                    print_result(
                        connection
                            .request(Command::CreateWindow {
                                session_id,
                                name,
                                cwd,
                            })
                            .await?,
                        cli.json,
                    )?;
                }
                ClientCommand::RenameWindow {
                    window_id, name, ..
                } => {
                    print_result(
                        connection
                            .request(Command::RenameWindow {
                                window_id,
                                new_name: name,
                            })
                            .await?,
                        cli.json,
                    )?;
                }
                ClientCommand::KillWindow {
                    window_id, confirm, ..
                } => {
                    require_confirmation(confirm, "kill-window")?;
                    print_result(
                        connection
                            .request(Command::KillWindow { window_id })
                            .await?,
                        cli.json,
                    )?;
                }
                ClientCommand::Attach {
                    session_id,
                    size_policy,
                    ..
                } => {
                    connection.attach(session_id, size_policy.into()).await?;
                }
                ClientCommand::Machines => unreachable!(),
            }
        }
    }
    Ok(())
}

fn command_target(command: &ClientCommand) -> Option<&Target> {
    match command {
        ClientCommand::Machines => None,
        ClientCommand::Sessions(target) => Some(target),
        ClientCommand::Windows { target, .. }
        | ClientCommand::Panes { target, .. }
        | ClientCommand::CreateSession { target, .. }
        | ClientCommand::RenameSession { target, .. }
        | ClientCommand::KillSession { target, .. }
        | ClientCommand::CreateWindow { target, .. }
        | ClientCommand::RenameWindow { target, .. }
        | ClientCommand::KillWindow { target, .. }
        | ClientCommand::Attach { target, .. } => Some(target),
    }
}

fn require_confirmation(confirm: bool, operation: &str) -> Result<()> {
    anyhow::ensure!(confirm, "{operation} is destructive; repeat with --confirm");
    Ok(())
}

impl ClientConnection {
    async fn connect(
        relay: &str,
        token: &str,
        client_id: Uuid,
        client_name: &str,
        machine: &str,
    ) -> Result<Self> {
        let (sink, source, machines) = handshake(relay, token, client_id, client_name).await?;
        let machine_id = resolve_machine(machine, &machines)?;
        Ok(Self {
            machine_id,
            sink,
            source,
        })
    }

    async fn request(&mut self, command: Command) -> Result<CommandResult> {
        let request_id = Uuid::new_v4();
        self.send_payload(ClientPayload::Request {
            request_id,
            command,
        })
        .await?;
        tokio::time::timeout(Duration::from_secs(15), async {
            loop {
                match self.next_agent_payload().await? {
                    AgentPayload::Response {
                        request_id: response_id,
                        result,
                    } if response_id == request_id => return Ok(result),
                    AgentPayload::RequestError {
                        request_id: response_id,
                        code,
                        message,
                    } if response_id == request_id => {
                        anyhow::bail!("agent error {code}: {message}")
                    }
                    _ => {}
                }
            }
        })
        .await
        .context("agent request timed out")?
    }

    async fn send_payload(&mut self, payload: ClientPayload) -> Result<()> {
        send_wire(
            &mut self.sink,
            WireMessage::RouteToAgent {
                machine_id: self.machine_id,
                payload,
            },
        )
        .await
    }

    async fn next_agent_payload(&mut self) -> Result<AgentPayload> {
        loop {
            let frame = self
                .source
                .next()
                .await
                .context("relay websocket closed")??;
            let Some(message) = parse_ws_frame(frame)? else {
                continue;
            };
            match message {
                WireMessage::DeliverToClient {
                    machine_id,
                    payload,
                } if machine_id == self.machine_id => {
                    return Ok(payload);
                }
                WireMessage::MachineOffline { machine_id } if machine_id == self.machine_id => {
                    anyhow::bail!("machine {} went offline", self.machine_id)
                }
                WireMessage::Ping { nonce } => {
                    send_wire(&mut self.sink, WireMessage::Pong { nonce }).await?;
                }
                WireMessage::Error { code, message } => {
                    anyhow::bail!("relay error {code}: {message}")
                }
                _ => {}
            }
        }
    }

    async fn attach(mut self, session_id: String, size_policy: SizePolicy) -> Result<()> {
        let (cols, rows) = size().context("read local terminal size")?;
        let result = self
            .request(Command::OpenTerminal {
                session_id,
                cols,
                rows,
                size_policy,
            })
            .await?;
        let (stream_id, ignore_size) = match result {
            CommandResult::TerminalOpened {
                stream_id,
                ignore_size,
            } => (stream_id, ignore_size),
            _ => anyhow::bail!("agent returned an unexpected result for open_terminal"),
        };

        eprintln!(
            "attached (stream {stream_id}, ignore-size={ignore_size}); detach with Ctrl-\\ then d"
        );
        let _raw_mode = RawModeGuard::enter()?;
        let mut stdin = tokio::io::stdin();
        let mut stdout = tokio::io::stdout();
        let mut input = vec![0_u8; 8192];
        let mut detach_prefix = false;
        let mut last_size = (cols, rows);
        let mut resize_tick = tokio::time::interval(Duration::from_millis(250));
        resize_tick.set_missed_tick_behavior(MissedTickBehavior::Skip);
        let mut heartbeat = tokio::time::interval(Duration::from_secs(20));
        heartbeat.set_missed_tick_behavior(MissedTickBehavior::Delay);

        let attach_result: Result<()> = loop {
            tokio::select! {
                read = stdin.read(&mut input) => {
                    let read = read.context("read local terminal input")?;
                    if read == 0 { break Ok(()) }
                    let (forward, detach) = filter_detach_escape(
                        &input[..read],
                        &mut detach_prefix,
                    );
                    if !forward.is_empty() {
                        self.send_payload(ClientPayload::TerminalInput {
                            stream_id,
                            data: terminal_bytes_to_text(&forward),
                        }).await?;
                    }
                    if detach { break Ok(()) }
                }
                frame = self.source.next() => {
                    let frame = frame.context("relay websocket closed")??;
                    let Some(message) = parse_ws_frame(frame)? else { continue };
                    match message {
                        WireMessage::DeliverToClient { machine_id, payload }
                            if machine_id == self.machine_id => {
                                match payload {
                                    AgentPayload::TerminalOutput { stream_id: output_id, data }
                                        if output_id == stream_id => {
                                            stdout.write_all(&terminal_text_to_bytes(&data)?).await?;
                                            stdout.flush().await?;
                                        }
                                    AgentPayload::TerminalClosed {
                                        stream_id: closed_id,
                                        reason,
                                        exit_code,
                                    } if closed_id == stream_id => {
                                        break Err(anyhow::anyhow!(
                                            "remote terminal closed ({reason}, exit code {exit_code:?})"
                                        ));
                                    }
                                    _ => {}
                                }
                            }
                        WireMessage::MachineOffline { machine_id } if machine_id == self.machine_id => {
                            break Err(anyhow::anyhow!("machine {} went offline", self.machine_id));
                        }
                        WireMessage::Ping { nonce } => {
                            send_wire(&mut self.sink, WireMessage::Pong { nonce }).await?;
                        }
                        WireMessage::Error { code, message } => {
                            break Err(anyhow::anyhow!("relay error {code}: {message}"));
                        }
                        _ => {}
                    }
                }
                _ = resize_tick.tick() => {
                    if let Ok(current_size) = size()
                        && current_size != last_size
                    {
                        last_size = current_size;
                        self.send_payload(ClientPayload::TerminalResize {
                            stream_id,
                            cols: current_size.0,
                            rows: current_size.1,
                        }).await?;
                    }
                }
                _ = heartbeat.tick() => {
                    send_wire(&mut self.sink, WireMessage::Ping { nonce: clock_nonce() }).await?;
                }
            }
        };

        let _ = self
            .send_payload(ClientPayload::TerminalDetach { stream_id })
            .await;
        attach_result
    }
}

struct RawModeGuard;

impl RawModeGuard {
    fn enter() -> Result<Self> {
        enable_raw_mode().context("enable local terminal raw mode")?;
        Ok(Self)
    }
}

impl Drop for RawModeGuard {
    fn drop(&mut self) {
        let _ = disable_raw_mode();
    }
}

fn filter_detach_escape(input: &[u8], pending: &mut bool) -> (Vec<u8>, bool) {
    let mut output = Vec::with_capacity(input.len());
    for &byte in input {
        if *pending {
            *pending = false;
            if byte == b'd' {
                return (output, true);
            }
            output.push(0x1c);
        }
        if byte == 0x1c {
            *pending = true;
        } else {
            output.push(byte);
        }
    }
    (output, false)
}

async fn list_machines(
    relay: &str,
    token: &str,
    client_id: Uuid,
    client_name: &str,
) -> Result<Vec<MachineInfo>> {
    let (_, _, machines) = handshake(relay, token, client_id, client_name).await?;
    Ok(machines)
}

/// Connect to the relay, complete the client hello, and return the socket plus
/// the online machine list the relay sends right after `Ready`.
async fn handshake(
    relay: &str,
    token: &str,
    client_id: Uuid,
    client_name: &str,
) -> Result<(SocketSink, SocketSource, Vec<MachineInfo>)> {
    validate_relay_url(relay)?;
    let endpoint = websocket_endpoint(relay, "ws/client");
    let (socket, _) =
        connect_async_tls_with_config(&endpoint, None, false, Some(relay_connector()))
            .await
            .with_context(|| format!("connect to relay {endpoint}"))?;
    let (mut sink, mut source) = socket.split();
    send_wire(
        &mut sink,
        WireMessage::ClientHello {
            protocol: PROTOCOL_VERSION,
            token: token.into(),
            client_id,
            client_name: client_name.into(),
        },
    )
    .await?;
    tokio::time::timeout(Duration::from_secs(10), async {
        let mut ready = false;
        let mut snapshot = None;
        loop {
            let frame = source.next().await.context("relay websocket closed")??;
            let Some(message) = parse_ws_frame(frame)? else {
                continue;
            };
            match message {
                WireMessage::Ready { .. } => ready = true,
                WireMessage::MachineSnapshot { machines } => snapshot = Some(machines),
                WireMessage::Ping { nonce } => {
                    send_wire(&mut sink, WireMessage::Pong { nonce }).await?
                }
                WireMessage::Error { code, message } => {
                    anyhow::bail!("relay error {code}: {message}")
                }
                _ => {}
            }
            if ready && let Some(machines) = snapshot {
                return Ok((sink, source, machines));
            }
        }
    })
    .await
    .context("relay handshake timed out")?
}

/// Resolve a `--machine` argument against the relay's online machine list:
/// exact UUID first, then exact name.
fn resolve_machine(selector: &str, machines: &[MachineInfo]) -> Result<Uuid> {
    if let Ok(id) = Uuid::parse_str(selector)
        && let Some(machine) = machines.iter().find(|m| m.id == id)
    {
        return Ok(machine.id);
    }
    let by_name: Vec<&MachineInfo> = machines.iter().filter(|m| m.name == selector).collect();
    match by_name.as_slice() {
        [machine] => Ok(machine.id),
        [] => {
            let online = machines
                .iter()
                .map(|m| format!("  {} ({})", m.name, m.id))
                .collect::<Vec<_>>()
                .join("\n");
            anyhow::bail!("machine {selector:?} is not online. Online machines:\n{online}")
        }
        many => {
            let matches = many
                .iter()
                .map(|m| format!("  {} ({})", m.name, m.id))
                .collect::<Vec<_>>()
                .join("\n");
            anyhow::bail!("machine name {selector:?} is ambiguous; use the id:\n{matches}")
        }
    }
}

async fn send_wire(sink: &mut SocketSink, message: WireMessage) -> Result<()> {
    let text = wire_to_text(&message)?;
    sink.send(Message::Text(text.into()))
        .await
        .context("send websocket message")
}

fn parse_ws_frame(frame: Message) -> Result<Option<WireMessage>> {
    match frame {
        Message::Text(text) => Ok(Some(wire_from_text(&text)?)),
        Message::Close(_) => anyhow::bail!("relay websocket closed"),
        Message::Ping(_) | Message::Pong(_) => Ok(None),
        Message::Binary(_) | Message::Frame(_) => anyhow::bail!("unsupported websocket frame"),
    }
}

fn print_machines(machines: &[MachineInfo], json: bool) -> Result<()> {
    if json {
        println!("{}", serde_json::to_string_pretty(machines)?);
        return Ok(());
    }
    if machines.is_empty() {
        println!("no machines online");
        return Ok(());
    }
    for machine in machines {
        println!(
            "{}\t{}\t{}/{}\tagent {}",
            machine.id, machine.name, machine.os, machine.arch, machine.agent_version
        );
    }
    Ok(())
}

fn print_result(result: CommandResult, json: bool) -> Result<()> {
    if json {
        println!("{}", serde_json::to_string_pretty(&result)?);
        return Ok(());
    }
    match result {
        CommandResult::Sessions { sessions } => {
            if sessions.is_empty() {
                println!("no tmux sessions");
            }
            for session in sessions {
                println!(
                    "{}\t{}\twindows={}\tattached={}",
                    session.id, session.name, session.windows, session.attached_clients
                );
            }
        }
        CommandResult::Windows { windows } => {
            for window in windows {
                println!(
                    "{}\t{}:{}\tpanes={}{}",
                    window.id,
                    window.index,
                    window.name,
                    window.panes,
                    if window.active { "\tactive" } else { "" }
                );
            }
        }
        CommandResult::Panes { panes } => {
            for pane in panes {
                println!(
                    "{}\t{}\t{}\t{}\t{}x{}{}",
                    pane.id,
                    pane.index,
                    pane.command,
                    pane.current_path,
                    pane.width,
                    pane.height,
                    if pane.active { "\tactive" } else { "" }
                );
            }
        }
        CommandResult::SessionCreated { session } => {
            println!("created session {} ({})", session.name, session.id);
        }
        CommandResult::WindowCreated { window } => {
            println!(
                "created window {}:{} ({})",
                window.index, window.name, window.id
            );
        }
        CommandResult::Acknowledged => println!("ok"),
        CommandResult::TerminalOpened {
            stream_id,
            ignore_size,
        } => {
            println!("opened terminal {stream_id} (ignore-size={ignore_size})");
        }
    }
    io::stdout().flush()?;
    Ok(())
}

fn websocket_endpoint(base: &str, path: &str) -> String {
    format!("{}/{}", base.trim_end_matches('/'), path)
}

fn validate_relay_url(url: &str) -> Result<()> {
    anyhow::ensure!(
        url.starts_with("ws://") || url.starts_with("wss://"),
        "relay URL must start with ws:// or wss://"
    );
    // Payloads are plaintext over the wire (protocol v2), so release builds
    // refuse unencrypted transport; ws:// is for development only.
    anyhow::ensure!(
        cfg!(debug_assertions) || url.starts_with("wss://"),
        "release builds require a wss:// relay URL (plaintext ws:// is only available in debug builds)"
    );
    Ok(())
}

fn clock_nonce() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detach_escape_is_consumed() {
        let mut pending = false;
        assert_eq!(
            filter_detach_escape(b"abc\x1c", &mut pending),
            (b"abc".to_vec(), false)
        );
        assert!(pending);
        assert_eq!(filter_detach_escape(b"d", &mut pending), (Vec::new(), true));
    }

    #[test]
    fn non_detach_escape_is_forwarded() {
        let mut pending = false;
        assert_eq!(
            filter_detach_escape(b"\x1cx", &mut pending),
            (b"\x1cx".to_vec(), false)
        );
    }
}
