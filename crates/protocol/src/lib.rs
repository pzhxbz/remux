#[cfg(feature = "client-tls")]
mod tls;
#[cfg(feature = "client-tls")]
pub use tls::relay_connector;

use anyhow::{Context, Result, bail};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub const PROTOCOL_VERSION: u16 = 2;
pub const MAX_WIRE_MESSAGE_BYTES: usize = 96 * 1024;
pub const MAX_TERMINAL_CHUNK_BYTES: usize = 32 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MachineInfo {
    pub id: Uuid,
    pub name: String,
    pub os: String,
    pub arch: String,
    pub agent_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum WireMessage {
    AgentHello {
        protocol: u16,
        token: String,
        machine: MachineInfo,
    },
    ClientHello {
        protocol: u16,
        token: String,
        client_id: Uuid,
        client_name: String,
    },
    Ready {
        connection_id: Uuid,
    },
    MachineSnapshot {
        machines: Vec<MachineInfo>,
    },
    MachineOnline {
        machine: MachineInfo,
    },
    MachineOffline {
        machine_id: Uuid,
    },
    RouteToAgent {
        machine_id: Uuid,
        payload: ClientPayload,
    },
    DeliverToAgent {
        client_id: Uuid,
        payload: ClientPayload,
    },
    RouteToClient {
        client_id: Uuid,
        payload: AgentPayload,
    },
    DeliverToClient {
        machine_id: Uuid,
        payload: AgentPayload,
    },
    ClientDisconnected {
        client_id: Uuid,
    },
    Ping {
        nonce: u64,
    },
    Pong {
        nonce: u64,
    },
    Error {
        code: String,
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SessionInfo {
    pub id: String,
    pub name: String,
    pub windows: u32,
    pub attached_clients: u32,
    pub created_at: i64,
    pub activity_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct WindowInfo {
    pub id: String,
    pub index: u32,
    pub name: String,
    pub active: bool,
    pub panes: u32,
    pub layout: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PaneInfo {
    pub id: String,
    pub index: u32,
    pub active: bool,
    pub command: String,
    pub current_path: String,
    pub width: u16,
    pub height: u16,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SizePolicy {
    Auto,
    PreserveExisting,
    TakeControl,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "command", rename_all = "snake_case")]
pub enum Command {
    ListSessions,
    ListWindows {
        session_id: String,
    },
    ListPanes {
        window_id: String,
    },
    CreateSession {
        name: String,
        cwd: Option<String>,
    },
    RenameSession {
        session_id: String,
        new_name: String,
    },
    KillSession {
        session_id: String,
    },
    CreateWindow {
        session_id: String,
        name: Option<String>,
        cwd: Option<String>,
    },
    RenameWindow {
        window_id: String,
        new_name: String,
    },
    KillWindow {
        window_id: String,
    },
    OpenTerminal {
        session_id: String,
        cols: u16,
        rows: u16,
        size_policy: SizePolicy,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientPayload {
    Request {
        request_id: Uuid,
        command: Command,
    },
    TerminalInput {
        stream_id: Uuid,
        data: String,
    },
    TerminalResize {
        stream_id: Uuid,
        cols: u16,
        rows: u16,
    },
    TerminalRefresh {
        stream_id: Uuid,
    },
    TerminalSelectWindow {
        stream_id: Uuid,
        window_id: String,
    },
    TerminalDetach {
        stream_id: Uuid,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "result", rename_all = "snake_case")]
pub enum CommandResult {
    Sessions { sessions: Vec<SessionInfo> },
    Windows { windows: Vec<WindowInfo> },
    Panes { panes: Vec<PaneInfo> },
    SessionCreated { session: SessionInfo },
    WindowCreated { window: WindowInfo },
    Acknowledged,
    TerminalOpened { stream_id: Uuid, ignore_size: bool },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AgentPayload {
    Response {
        request_id: Uuid,
        result: CommandResult,
    },
    RequestError {
        request_id: Uuid,
        code: String,
        message: String,
    },
    TerminalOutput {
        stream_id: Uuid,
        data: String,
    },
    TerminalClosed {
        stream_id: Uuid,
        reason: String,
        exit_code: Option<u32>,
    },
}

pub fn terminal_bytes_to_text(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn terminal_text_to_bytes(text: &str) -> Result<Vec<u8>> {
    URL_SAFE_NO_PAD
        .decode(text)
        .context("terminal payload is not valid base64url")
}

pub fn wire_to_text(message: &WireMessage) -> Result<String> {
    let text = serde_json::to_string(message).context("serialize wire message")?;
    if text.len() > MAX_WIRE_MESSAGE_BYTES {
        bail!("wire message exceeds maximum size");
    }
    Ok(text)
}

pub fn wire_from_text(text: &str) -> Result<WireMessage> {
    if text.len() > MAX_WIRE_MESSAGE_BYTES {
        bail!("wire message exceeds maximum size");
    }
    serde_json::from_str(text).context("decode wire message")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terminal_refresh_uses_the_v1_extension_wire_format() {
        let stream_id = Uuid::parse_str("01890f5e-b080-7cc0-98d2-a0f9d1f43c03").unwrap();
        let encoded = serde_json::to_string(&ClientPayload::TerminalRefresh { stream_id }).unwrap();

        assert_eq!(
            encoded,
            r#"{"type":"terminal_refresh","stream_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c03"}"#
        );
    }

    #[test]
    fn terminal_window_selection_uses_fixed_identifiers() {
        let stream_id = Uuid::parse_str("01890f5e-b080-7cc0-98d2-a0f9d1f43c03").unwrap();
        let encoded = serde_json::to_string(&ClientPayload::TerminalSelectWindow {
            stream_id,
            window_id: "@7".into(),
        })
        .unwrap();

        assert_eq!(
            encoded,
            r#"{"type":"terminal_select_window","stream_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c03","window_id":"@7"}"#
        );
    }

    #[test]
    fn route_to_agent_carries_plaintext_payload() {
        let machine_id = Uuid::parse_str("01890f5e-b080-7cc0-98d2-a0f9d1f43c01").unwrap();
        let stream_id = Uuid::parse_str("01890f5e-b080-7cc0-98d2-a0f9d1f43c03").unwrap();
        let encoded = wire_to_text(&WireMessage::RouteToAgent {
            machine_id,
            payload: ClientPayload::TerminalRefresh { stream_id },
        })
        .unwrap();

        assert_eq!(
            encoded,
            r#"{"type":"route_to_agent","machine_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c01","payload":{"type":"terminal_refresh","stream_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c03"}}"#
        );
        // And a v1-shaped message with `sealed` must not parse anymore.
        assert!(wire_from_text(
            r#"{"type":"route_to_agent","machine_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c01","sealed":{"nonce":"AA","ciphertext":"AA"}}"#
        )
        .is_err());
    }
}
