mod config;
mod terminal;
mod tmux;

use std::{
    io::{self, Write},
    path::{Path, PathBuf},
    time::Duration,
};

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use config::{AgentConfig, default_config_path, default_machine_name, default_pairing_path};
use futures_util::{Sink, SinkExt, StreamExt};
use remux_protocol::{
    AgentPayload, ClientPayload, Command, CommandResult, MachineInfo, PROTOCOL_VERSION,
    WireMessage, agent_aad, client_aad, decode_secret, open, relay_connector, seal,
    terminal_text_to_bytes, wire_from_text, wire_to_text,
};
use terminal::{AgentEvent, TerminalManager};
use tokio::{sync::mpsc, time::MissedTickBehavior};
use tokio_tungstenite::{connect_async_tls_with_config, tungstenite::Message};
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;
use uuid::Uuid;

#[derive(Debug, Parser)]
#[command(name = "remux", version, about = "RemoteMux tmux host agent")]
struct Args {
    #[command(subcommand)]
    command: AgentCommand,
}

#[derive(Debug, Subcommand)]
enum AgentCommand {
    /// Configure this machine and generate a client pairing bundle.
    #[command(alias = "init")]
    Config {
        #[arg(long, env = "REMUX_CONFIG", default_value_os_t = default_config_path())]
        config: PathBuf,

        /// Output path; defaults to pairing.toml beside the agent config.
        #[arg(long)]
        pairing: Option<PathBuf>,

        #[arg(long)]
        relay: Option<String>,

        #[arg(long, env = "REMUX_AGENT_TOKEN", hide_env_values = true)]
        token: Option<String>,

        #[arg(long, default_value_t = default_machine_name())]
        name: String,

        #[arg(long, default_value = "tmux")]
        tmux: String,
    },

    /// Connect to the relay and serve tmux management/terminal requests.
    Run {
        #[arg(long, env = "REMUX_CONFIG", default_value_os_t = default_config_path())]
        config: PathBuf,
    },

    /// Check the local config and tmux installation without connecting.
    Doctor {
        #[arg(long, env = "REMUX_CONFIG", default_value_os_t = default_config_path())]
        config: PathBuf,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    // tokio-tungstenite pulls in rustls without a default CryptoProvider, so
    // select one explicitly before any wss:// connection is attempted.
    rustls::crypto::ring::default_provider()
        .install_default()
        .ok();

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .with_target(false)
        .init();

    match Args::parse().command {
        AgentCommand::Config {
            config,
            pairing,
            relay,
            token,
            name,
            tmux,
        } => {
            let relay = match relay {
                Some(relay) => relay,
                None => prompt_nonempty("Relay URL (ws:// or wss://): ")?,
            };
            let token = match token {
                Some(token) => token,
                None => rpassword::prompt_password("Relay agent token: ")?,
            };
            anyhow::ensure!(
                token.len() >= 16,
                "relay agent token must be at least 16 characters"
            );
            validate_relay_url(&relay)?;
            anyhow::ensure!(!name.trim().is_empty(), "machine name cannot be empty");
            let pairing = pairing.unwrap_or_else(|| default_pairing_path(&config));
            let config_value = AgentConfig::new(relay, token, name, tmux);
            config_value.save(&config)?;
            config_value.save_pairing(&pairing)?;
            println!("agent config: {}", config.display());
            println!("client pairing bundle: {}", pairing.display());
            println!("machine id: {}", config_value.machine_id);
            println!("next: remux run --config {}", config.display());
        }
        AgentCommand::Doctor { config } => doctor(&config)?,
        AgentCommand::Run { config } => run_forever(AgentConfig::load(&config)?).await?,
    }
    Ok(())
}

fn prompt_nonempty(prompt: &str) -> Result<String> {
    print!("{prompt}");
    io::stdout().flush().context("flush configuration prompt")?;
    let mut value = String::new();
    io::stdin()
        .read_line(&mut value)
        .context("read configuration value")?;
    let value = value.trim().to_owned();
    anyhow::ensure!(!value.is_empty(), "configuration value cannot be empty");
    Ok(value)
}

fn doctor(path: &Path) -> Result<()> {
    let config = AgentConfig::load(path)?;
    validate_relay_url(&config.relay_url)?;
    let _ = decode_secret(&config.machine_secret)?;
    let tmux = tmux::Tmux::new(config.tmux_binary.clone());
    let version = tmux.version()?;
    let sessions = tmux.list_sessions()?;
    println!("machine: {} ({})", config.machine_name, config.machine_id);
    println!("relay: {}", config.relay_url);
    println!("tmux: {version}");
    println!("sessions visible to this user: {}", sessions.len());
    println!("network mode: outbound websocket only; no local listening socket");
    Ok(())
}

async fn run_forever(config: AgentConfig) -> Result<()> {
    validate_relay_url(&config.relay_url)?;
    let secret = decode_secret(&config.machine_secret)?;
    let tmux = tmux::Tmux::new(config.tmux_binary.clone());
    info!(version = %tmux.version()?, "tmux available");

    let (event_tx, mut event_rx) = mpsc::channel(256);
    let terminals = TerminalManager::new(tmux, event_tx);
    let mut retry_seconds = 1_u64;

    loop {
        match run_connection(
            &config,
            &secret,
            &terminals,
            &mut event_rx,
            &mut retry_seconds,
        )
        .await
        {
            Ok(()) => warn!("relay connection closed"),
            // `{:#}` renders the whole anyhow chain; plain Display hides the root cause.
            Err(error) => warn!(error = format!("{error:#}"), "relay connection failed"),
        }
        // Only the temporary tmux attach clients are stopped. tmux sessions keep running.
        terminals.detach_all();
        tokio::time::sleep(Duration::from_secs(retry_seconds)).await;
        retry_seconds = (retry_seconds * 2).min(30);
        info!(retry_seconds, "reconnecting to relay");
    }
}

async fn run_connection(
    config: &AgentConfig,
    secret: &[u8; 32],
    terminals: &TerminalManager,
    event_rx: &mut mpsc::Receiver<AgentEvent>,
    retry_seconds: &mut u64,
) -> Result<()> {
    let endpoint = websocket_endpoint(&config.relay_url, "ws/agent");
    let (socket, _) =
        connect_async_tls_with_config(&endpoint, None, false, Some(relay_connector()))
            .await
            .with_context(|| format!("connect to relay {endpoint}"))?;
    let (mut sink, mut source) = socket.split();
    let machine = MachineInfo {
        id: config.machine_id,
        name: config.machine_name.clone(),
        os: std::env::consts::OS.into(),
        arch: std::env::consts::ARCH.into(),
        agent_version: env!("CARGO_PKG_VERSION").into(),
    };
    send_wire(
        &mut sink,
        WireMessage::AgentHello {
            protocol: PROTOCOL_VERSION,
            token: config.relay_token.clone(),
            machine,
        },
    )
    .await?;

    let mut heartbeat = tokio::time::interval(Duration::from_secs(20));
    heartbeat.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut ready = false;

    loop {
        tokio::select! {
            frame = source.next() => {
                let message = parse_ws_frame(frame.context("relay websocket closed")??)?;
                let Some(message) = message else { continue };
                match message {
                    WireMessage::Ready { connection_id } => {
                        ready = true;
                        *retry_seconds = 1;
                        info!(%connection_id, machine_id = %config.machine_id, "agent connected");
                    }
                    WireMessage::DeliverToAgent { client_id, sealed } => {
                        let aad = client_aad(config.machine_id, client_id);
                        match open::<ClientPayload>(secret, aad.as_bytes(), &sealed) {
                            Ok(payload) => {
                                if let Err(error) = handle_client_payload(
                                    config.machine_id,
                                    secret,
                                    client_id,
                                    payload,
                                    terminals,
                                    &mut sink,
                                ).await {
                                    warn!(%client_id, %error, "client payload failed");
                                }
                            }
                            Err(error) => warn!(%client_id, %error, "rejected encrypted client payload"),
                        }
                    }
                    WireMessage::ClientDisconnected { client_id } => terminals.detach_client(client_id),
                    WireMessage::Ping { nonce } => send_wire(&mut sink, WireMessage::Pong { nonce }).await?,
                    WireMessage::Pong { .. } => {}
                    WireMessage::Error { code, message } => anyhow::bail!("relay error {code}: {message}"),
                    _ => warn!("ignored unexpected relay message"),
                }
            }
            event = event_rx.recv() => {
                let event = event.context("terminal event channel closed")?;
                match event {
                    AgentEvent::Payload { client_id, payload } => {
                        send_agent_payload(config.machine_id, secret, client_id, payload, &mut sink).await?;
                    }
                    AgentEvent::Exited { client_id, stream_id, exit_code } => {
                        terminals.cleanup(stream_id);
                        send_agent_payload(
                            config.machine_id,
                            secret,
                            client_id,
                            AgentPayload::TerminalClosed {
                                stream_id,
                                reason: "tmux attach client exited".into(),
                                exit_code,
                            },
                            &mut sink,
                        ).await?;
                    }
                }
            }
            _ = heartbeat.tick(), if ready => {
                send_wire(&mut sink, WireMessage::Ping { nonce: rand_nonce() }).await?;
            }
        }
    }
}

async fn handle_client_payload<S>(
    machine_id: Uuid,
    secret: &[u8; 32],
    client_id: Uuid,
    payload: ClientPayload,
    terminals: &TerminalManager,
    sink: &mut S,
) -> Result<()>
where
    S: Sink<Message> + Unpin,
    S::Error: std::error::Error + Send + Sync + 'static,
{
    match payload {
        ClientPayload::Request {
            request_id,
            command,
        } => {
            let response = match execute_command(command, terminals, client_id) {
                Ok(result) => AgentPayload::Response { request_id, result },
                Err(error) => AgentPayload::RequestError {
                    request_id,
                    code: "command_failed".into(),
                    message: format!("{error:#}"),
                },
            };
            send_agent_payload(machine_id, secret, client_id, response, sink).await?;
        }
        ClientPayload::TerminalInput { stream_id, data } => {
            terminals.input(client_id, stream_id, &terminal_text_to_bytes(&data)?)?;
        }
        ClientPayload::TerminalResize {
            stream_id,
            cols,
            rows,
        } => {
            terminals.resize(client_id, stream_id, cols, rows)?;
        }
        ClientPayload::TerminalRefresh { stream_id } => {
            terminals.refresh(client_id, stream_id)?;
        }
        ClientPayload::TerminalSelectWindow {
            stream_id,
            window_id,
        } => {
            terminals.select_window(client_id, stream_id, &window_id)?;
        }
        ClientPayload::TerminalDetach { stream_id } => {
            terminals.detach(client_id, stream_id)?;
        }
    }
    Ok(())
}

fn execute_command(
    command: Command,
    terminals: &TerminalManager,
    client_id: Uuid,
) -> Result<CommandResult> {
    let tmux = terminals.tmux();
    match command {
        Command::ListSessions => Ok(CommandResult::Sessions {
            sessions: tmux.list_sessions()?,
        }),
        Command::ListWindows { session_id } => Ok(CommandResult::Windows {
            windows: tmux.list_windows(&session_id)?,
        }),
        Command::ListPanes { window_id } => Ok(CommandResult::Panes {
            panes: tmux.list_panes(&window_id)?,
        }),
        Command::CreateSession { name, cwd } => Ok(CommandResult::SessionCreated {
            session: tmux.create_session(&name, cwd.as_deref())?,
        }),
        Command::RenameSession {
            session_id,
            new_name,
        } => {
            tmux.rename_session(&session_id, &new_name)?;
            Ok(CommandResult::Acknowledged)
        }
        Command::KillSession { session_id } => {
            tmux.kill_session(&session_id)?;
            Ok(CommandResult::Acknowledged)
        }
        Command::CreateWindow {
            session_id,
            name,
            cwd,
        } => Ok(CommandResult::WindowCreated {
            window: tmux.create_window(&session_id, name.as_deref(), cwd.as_deref())?,
        }),
        Command::RenameWindow {
            window_id,
            new_name,
        } => {
            tmux.rename_window(&window_id, &new_name)?;
            Ok(CommandResult::Acknowledged)
        }
        Command::KillWindow { window_id } => {
            tmux.kill_window(&window_id)?;
            Ok(CommandResult::Acknowledged)
        }
        Command::OpenTerminal {
            session_id,
            cols,
            rows,
            size_policy,
        } => {
            let (stream_id, ignore_size) =
                terminals.open(client_id, &session_id, cols, rows, size_policy)?;
            Ok(CommandResult::TerminalOpened {
                stream_id,
                ignore_size,
            })
        }
    }
}

async fn send_agent_payload<S>(
    machine_id: Uuid,
    secret: &[u8; 32],
    client_id: Uuid,
    payload: AgentPayload,
    sink: &mut S,
) -> Result<()>
where
    S: Sink<Message> + Unpin,
    S::Error: std::error::Error + Send + Sync + 'static,
{
    let aad = agent_aad(machine_id, client_id);
    let sealed = seal(secret, aad.as_bytes(), &payload)?;
    send_wire(sink, WireMessage::RouteToClient { client_id, sealed }).await
}

async fn send_wire<S>(sink: &mut S, message: WireMessage) -> Result<()>
where
    S: Sink<Message> + Unpin,
    S::Error: std::error::Error + Send + Sync + 'static,
{
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

fn websocket_endpoint(base: &str, path: &str) -> String {
    format!("{}/{}", base.trim_end_matches('/'), path)
}

fn validate_relay_url(url: &str) -> Result<()> {
    anyhow::ensure!(
        url.starts_with("ws://") || url.starts_with("wss://"),
        "relay URL must start with ws:// or wss://"
    );
    Ok(())
}

fn rand_nonce() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64
}
