use std::{collections::HashMap, net::SocketAddr, sync::Arc, time::Duration};

use anyhow::{Context, Result};
use axum::{
    Json, Router,
    extract::{
        State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::Uri,
    response::Response,
    routing::get,
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use clap::Parser;
use futures_util::{SinkExt, StreamExt};
use remux_protocol::{
    MAX_WIRE_MESSAGE_BYTES, MachineInfo, PROTOCOL_VERSION, WireMessage, wire_from_text,
    wire_to_text,
};
use serde_json::{Value, json};
use subtle::ConstantTimeEq;
use tokio::{
    net::TcpListener,
    sync::{RwLock, mpsc},
    time::timeout,
};
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;
use uuid::Uuid;

const OUTBOUND_QUEUE: usize = 256;
const HELLO_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Parser)]
#[command(
    name = "remux-relay",
    version,
    about = "RemoteMux encrypted routing relay"
)]
struct Args {
    #[arg(long, env = "REMUX_RELAY_LISTEN", default_value = "127.0.0.1:8787")]
    listen: SocketAddr,

    #[arg(long, env = "REMUX_AGENT_TOKEN", hide_env_values = true)]
    agent_token: String,

    #[arg(long, env = "REMUX_CLIENT_TOKEN", hide_env_values = true)]
    client_token: String,

    /// Public ws:// or wss:// base URL printed in the Android quick-connect code.
    #[arg(long, env = "REMUX_PUBLIC_URL")]
    public_url: Option<String>,
}

#[derive(Clone)]
struct AgentEntry {
    connection_id: Uuid,
    machine: MachineInfo,
    tx: mpsc::Sender<WireMessage>,
}

#[derive(Clone)]
struct ClientEntry {
    connection_id: Uuid,
    tx: mpsc::Sender<WireMessage>,
}

struct RelayState {
    agent_token: String,
    client_token: String,
    agents: RwLock<HashMap<Uuid, AgentEntry>>,
    clients: RwLock<HashMap<Uuid, ClientEntry>>,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .with_target(false)
        .init();

    let args = Args::parse();
    validate_token("agent", &args.agent_token)?;
    validate_token("client", &args.client_token)?;
    let public_url = args
        .public_url
        .unwrap_or_else(|| format!("ws://{}", args.listen));
    let quick_connect = quick_connect_code(&public_url, &args.client_token)?;
    if args.listen.ip().is_unspecified() && public_url.contains("0.0.0.0") {
        warn!("quick-connect address is not remotely reachable; set --public-url");
    }
    println!("REMUX_APP_CONFIG={quick_connect}");
    println!("This line contains the client credential; treat it as a secret.");

    let state = Arc::new(RelayState {
        agent_token: args.agent_token,
        client_token: args.client_token,
        agents: RwLock::new(HashMap::new()),
        clients: RwLock::new(HashMap::new()),
    });

    let app = Router::new()
        .route("/healthz", get(healthz))
        .route("/ws/agent", get(agent_upgrade))
        .route("/ws/client", get(client_upgrade))
        .with_state(state);

    let listener = TcpListener::bind(args.listen)
        .await
        .with_context(|| format!("bind relay to {}", args.listen))?;
    info!(listen = %args.listen, "relay listening; terminate TLS in front of this socket in production");
    axum::serve(listener, app).await.context("serve relay")
}

fn quick_connect_code(public_url: &str, client_token: &str) -> Result<String> {
    let base = public_url.trim().trim_end_matches('/');
    let uri: Uri = base.parse().context("public URL is invalid")?;
    anyhow::ensure!(
        matches!(uri.scheme_str(), Some("ws" | "wss")),
        "public URL must start with ws:// or wss://"
    );
    let authority = uri
        .authority()
        .context("public URL must include a server host")?;
    anyhow::ensure!(
        authority_has_valid_host_and_port(authority.as_str())
            && uri.path() == "/"
            && uri.query().is_none(),
        "public URL must contain only a host and optional port"
    );
    Ok(format!(
        "{base}/~{}",
        URL_SAFE_NO_PAD.encode(client_token.as_bytes())
    ))
}

fn authority_has_valid_host_and_port(authority: &str) -> bool {
    if authority.is_empty() || authority.contains('@') {
        return false;
    }
    if let Some(bracketed) = authority.strip_prefix('[') {
        let Some((host, suffix)) = bracketed.split_once(']') else {
            return false;
        };
        return !host.is_empty()
            && (suffix.is_empty()
                || suffix
                    .strip_prefix(':')
                    .is_some_and(|port| port.parse::<u16>().is_ok()));
    }
    match authority.split_once(':') {
        Some((host, port)) => {
            !host.is_empty() && !port.contains(':') && port.parse::<u16>().is_ok()
        }
        None => true,
    }
}

fn validate_token(kind: &str, token: &str) -> Result<()> {
    anyhow::ensure!(
        token.len() >= 16,
        "{kind} token must be at least 16 characters"
    );
    Ok(())
}

async fn healthz(State(state): State<Arc<RelayState>>) -> Json<Value> {
    Json(json!({
        "status": "ok",
        "agents": state.agents.read().await.len(),
        "clients": state.clients.read().await.len(),
        "protocol": PROTOCOL_VERSION,
    }))
}

async fn agent_upgrade(ws: WebSocketUpgrade, State(state): State<Arc<RelayState>>) -> Response {
    ws.max_message_size(MAX_WIRE_MESSAGE_BYTES)
        .max_frame_size(MAX_WIRE_MESSAGE_BYTES)
        .on_upgrade(move |socket| handle_agent(socket, state))
}

async fn client_upgrade(ws: WebSocketUpgrade, State(state): State<Arc<RelayState>>) -> Response {
    ws.max_message_size(MAX_WIRE_MESSAGE_BYTES)
        .max_frame_size(MAX_WIRE_MESSAGE_BYTES)
        .on_upgrade(move |socket| handle_client(socket, state))
}

async fn handle_agent(mut socket: WebSocket, state: Arc<RelayState>) {
    let hello = match receive_hello(&mut socket).await {
        Ok(WireMessage::AgentHello {
            protocol,
            token,
            machine,
        }) => {
            if protocol != PROTOCOL_VERSION {
                send_direct_error(
                    &mut socket,
                    "protocol_mismatch",
                    "unsupported protocol version",
                )
                .await;
                return;
            }
            if !tokens_equal(&state.agent_token, &token) {
                send_direct_error(&mut socket, "unauthorized", "invalid agent token").await;
                return;
            }
            machine
        }
        Ok(_) => {
            send_direct_error(&mut socket, "bad_hello", "expected agent_hello").await;
            return;
        }
        Err(error) => {
            warn!(%error, "agent failed hello");
            return;
        }
    };

    let connection_id = Uuid::new_v4();
    let machine_id = hello.id;
    let machine_name = hello.name.clone();
    let (mut sink, mut source) = socket.split();
    let (tx, mut rx) = mpsc::channel::<WireMessage>(OUTBOUND_QUEUE);
    let writer = tokio::spawn(async move {
        while let Some(message) = rx.recv().await {
            let Ok(text) = wire_to_text(&message) else {
                break;
            };
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    });

    let previous = state.agents.write().await.insert(
        machine_id,
        AgentEntry {
            connection_id,
            machine: hello.clone(),
            tx: tx.clone(),
        },
    );
    if let Some(previous) = previous {
        let _ = previous
            .tx
            .send(WireMessage::Error {
                code: "replaced".into(),
                message: "a newer connection replaced this agent connection".into(),
            })
            .await;
    }
    let _ = tx.send(WireMessage::Ready { connection_id }).await;
    broadcast_clients(&state, WireMessage::MachineOnline { machine: hello }).await;
    info!(%machine_id, machine = %machine_name, "agent online");

    while let Some(frame) = source.next().await {
        let message = match parse_axum_frame(frame) {
            Ok(Some(message)) => message,
            Ok(None) => continue,
            Err(error) => {
                warn!(%machine_id, %error, "invalid agent frame");
                break;
            }
        };
        match message {
            WireMessage::RouteToClient { client_id, sealed } => {
                let client = state.clients.read().await.get(&client_id).cloned();
                if let Some(client) = client {
                    let _ = client
                        .tx
                        .send(WireMessage::DeliverToClient { machine_id, sealed })
                        .await;
                }
            }
            WireMessage::Ping { nonce } => {
                let _ = tx.send(WireMessage::Pong { nonce }).await;
            }
            WireMessage::Pong { .. } => {}
            _ => {
                let _ = tx
                    .send(WireMessage::Error {
                        code: "invalid_agent_message".into(),
                        message: "message is not valid on an agent connection".into(),
                    })
                    .await;
            }
        }
    }

    let removed = {
        let mut agents = state.agents.write().await;
        if agents
            .get(&machine_id)
            .is_some_and(|entry| entry.connection_id == connection_id)
        {
            agents.remove(&machine_id);
            true
        } else {
            false
        }
    };
    if removed {
        broadcast_clients(&state, WireMessage::MachineOffline { machine_id }).await;
        info!(%machine_id, machine = %machine_name, "agent offline");
    }
    writer.abort();
}

async fn handle_client(mut socket: WebSocket, state: Arc<RelayState>) {
    let (client_id, client_name) = match receive_hello(&mut socket).await {
        Ok(WireMessage::ClientHello {
            protocol,
            token,
            client_id,
            client_name,
        }) => {
            if protocol != PROTOCOL_VERSION {
                send_direct_error(
                    &mut socket,
                    "protocol_mismatch",
                    "unsupported protocol version",
                )
                .await;
                return;
            }
            if !tokens_equal(&state.client_token, &token) {
                send_direct_error(&mut socket, "unauthorized", "invalid client token").await;
                return;
            }
            (client_id, client_name)
        }
        Ok(_) => {
            send_direct_error(&mut socket, "bad_hello", "expected client_hello").await;
            return;
        }
        Err(error) => {
            warn!(%error, "client failed hello");
            return;
        }
    };

    let connection_id = Uuid::new_v4();
    let (mut sink, mut source) = socket.split();
    let (tx, mut rx) = mpsc::channel::<WireMessage>(OUTBOUND_QUEUE);
    let writer = tokio::spawn(async move {
        while let Some(message) = rx.recv().await {
            let Ok(text) = wire_to_text(&message) else {
                break;
            };
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    });

    let previous = state.clients.write().await.insert(
        client_id,
        ClientEntry {
            connection_id,
            tx: tx.clone(),
        },
    );
    if let Some(previous) = previous {
        let _ = previous
            .tx
            .send(WireMessage::Error {
                code: "replaced".into(),
                message: "a newer connection replaced this client connection".into(),
            })
            .await;
    }
    let machines = state
        .agents
        .read()
        .await
        .values()
        .map(|entry| entry.machine.clone())
        .collect();
    let _ = tx.send(WireMessage::Ready { connection_id }).await;
    let _ = tx.send(WireMessage::MachineSnapshot { machines }).await;
    info!(%client_id, client = %client_name, "client online");

    while let Some(frame) = source.next().await {
        let message = match parse_axum_frame(frame) {
            Ok(Some(message)) => message,
            Ok(None) => continue,
            Err(error) => {
                warn!(%client_id, %error, "invalid client frame");
                break;
            }
        };
        match message {
            WireMessage::RouteToAgent { machine_id, sealed } => {
                let agent = state.agents.read().await.get(&machine_id).cloned();
                if let Some(agent) = agent {
                    if agent
                        .tx
                        .send(WireMessage::DeliverToAgent { client_id, sealed })
                        .await
                        .is_err()
                    {
                        let _ = tx
                            .send(WireMessage::Error {
                                code: "agent_unavailable".into(),
                                message: "agent connection closed".into(),
                            })
                            .await;
                    }
                } else {
                    let _ = tx
                        .send(WireMessage::Error {
                            code: "machine_offline".into(),
                            message: format!("machine {machine_id} is offline"),
                        })
                        .await;
                }
            }
            WireMessage::Ping { nonce } => {
                let _ = tx.send(WireMessage::Pong { nonce }).await;
            }
            WireMessage::Pong { .. } => {}
            _ => {
                let _ = tx
                    .send(WireMessage::Error {
                        code: "invalid_client_message".into(),
                        message: "message is not valid on a client connection".into(),
                    })
                    .await;
            }
        }
    }

    let removed = {
        let mut clients = state.clients.write().await;
        if clients
            .get(&client_id)
            .is_some_and(|entry| entry.connection_id == connection_id)
        {
            clients.remove(&client_id);
            true
        } else {
            false
        }
    };
    if removed {
        broadcast_agents(&state, WireMessage::ClientDisconnected { client_id }).await;
        info!(%client_id, client = %client_name, "client offline");
    }
    writer.abort();
}

async fn receive_hello(socket: &mut WebSocket) -> Result<WireMessage> {
    let frame = timeout(HELLO_TIMEOUT, socket.recv())
        .await
        .context("hello timed out")?
        .context("connection closed before hello")?
        .context("read hello frame")?;
    match frame {
        Message::Text(text) => wire_from_text(&text),
        _ => anyhow::bail!("hello must be a text frame"),
    }
}

fn parse_axum_frame(frame: Result<Message, axum::Error>) -> Result<Option<WireMessage>> {
    match frame.context("read websocket frame")? {
        Message::Text(text) => Ok(Some(wire_from_text(&text)?)),
        Message::Close(_) => anyhow::bail!("websocket closed"),
        Message::Ping(_) | Message::Pong(_) => Ok(None),
        Message::Binary(_) => {
            anyhow::bail!("binary websocket frames are not supported in protocol v1")
        }
    }
}

async fn send_direct_error(socket: &mut WebSocket, code: &str, message: &str) {
    let wire = WireMessage::Error {
        code: code.into(),
        message: message.into(),
    };
    if let Ok(text) = wire_to_text(&wire) {
        let _ = socket.send(Message::Text(text.into())).await;
    }
    let _ = socket.close().await;
}

async fn broadcast_clients(state: &RelayState, message: WireMessage) {
    let clients: Vec<_> = state
        .clients
        .read()
        .await
        .values()
        .map(|entry| entry.tx.clone())
        .collect();
    for client in clients {
        let _ = client.send(message.clone()).await;
    }
}

async fn broadcast_agents(state: &RelayState, message: WireMessage) {
    let agents: Vec<_> = state
        .agents
        .read()
        .await
        .values()
        .map(|entry| entry.tx.clone())
        .collect();
    for agent in agents {
        let _ = agent.send(message.clone()).await;
    }
}

fn tokens_equal(expected: &str, supplied: &str) -> bool {
    expected.len() == supplied.len() && bool::from(expected.as_bytes().ct_eq(supplied.as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quick_connect_is_single_line_and_url_safe() {
        let code =
            quick_connect_code("wss://relay.example.com:443/", "client/token with spaces").unwrap();

        assert_eq!(
            code,
            "wss://relay.example.com:443/~Y2xpZW50L3Rva2VuIHdpdGggc3BhY2Vz"
        );
    }

    #[test]
    fn quick_connect_rejects_public_paths() {
        assert!(quick_connect_code("wss://relay.example.com/base", "token").is_err());
        assert!(quick_connect_code("wss://user@relay.example.com", "token").is_err());
        assert!(quick_connect_code("wss://relay.example.com:invalid", "token").is_err());
    }

    #[test]
    fn quick_connect_accepts_ipv6_authority() {
        assert_eq!(
            quick_connect_code("ws://[::1]:8787", "client-token-0001").unwrap(),
            "ws://[::1]:8787/~Y2xpZW50LXRva2VuLTAwMDE"
        );
    }
}
