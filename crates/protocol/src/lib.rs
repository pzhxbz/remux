use anyhow::{Context, Result, bail};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use chacha20poly1305::{
    ChaCha20Poly1305, Key, Nonce,
    aead::{Aead, KeyInit, Payload},
};
use rand::{RngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub const PROTOCOL_VERSION: u16 = 1;
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
pub struct SealedPayload {
    pub nonce: String,
    pub ciphertext: String,
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
        sealed: SealedPayload,
    },
    DeliverToAgent {
        client_id: Uuid,
        sealed: SealedPayload,
    },
    RouteToClient {
        client_id: Uuid,
        sealed: SealedPayload,
    },
    DeliverToClient {
        machine_id: Uuid,
        sealed: SealedPayload,
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PairingBundle {
    pub version: u16,
    pub relay_url: String,
    pub machine_id: Uuid,
    pub machine_name: String,
    pub machine_secret: String,
}

pub fn generate_secret() -> [u8; 32] {
    let mut secret = [0_u8; 32];
    OsRng.fill_bytes(&mut secret);
    secret
}

pub fn encode_secret(secret: &[u8; 32]) -> String {
    URL_SAFE_NO_PAD.encode(secret)
}

pub fn decode_secret(encoded: &str) -> Result<[u8; 32]> {
    let bytes = URL_SAFE_NO_PAD
        .decode(encoded)
        .context("machine secret is not valid base64url")?;
    bytes
        .try_into()
        .map_err(|_| anyhow::anyhow!("machine secret must be exactly 32 bytes"))
}

pub fn terminal_bytes_to_text(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn terminal_text_to_bytes(text: &str) -> Result<Vec<u8>> {
    URL_SAFE_NO_PAD
        .decode(text)
        .context("terminal payload is not valid base64url")
}

pub fn client_aad(machine_id: Uuid, client_id: Uuid) -> String {
    format!("remux/v1/client-to-agent/{machine_id}/{client_id}")
}

pub fn agent_aad(machine_id: Uuid, client_id: Uuid) -> String {
    format!("remux/v1/agent-to-client/{machine_id}/{client_id}")
}

pub fn seal<T: Serialize>(secret: &[u8; 32], aad: &[u8], value: &T) -> Result<SealedPayload> {
    let plaintext = serde_json::to_vec(value).context("serialize encrypted payload")?;
    let key: Key = (*secret).into();
    let cipher = ChaCha20Poly1305::new(&key);
    let mut nonce_bytes = [0_u8; 12];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce: Nonce = nonce_bytes.into();
    let ciphertext = cipher
        .encrypt(
            &nonce,
            Payload {
                msg: &plaintext,
                aad,
            },
        )
        .map_err(|_| anyhow::anyhow!("encrypt payload"))?;
    Ok(SealedPayload {
        nonce: URL_SAFE_NO_PAD.encode(nonce_bytes),
        ciphertext: URL_SAFE_NO_PAD.encode(ciphertext),
    })
}

pub fn open<T: for<'de> Deserialize<'de>>(
    secret: &[u8; 32],
    aad: &[u8],
    sealed: &SealedPayload,
) -> Result<T> {
    let nonce = URL_SAFE_NO_PAD
        .decode(&sealed.nonce)
        .context("decode encrypted nonce")?;
    let nonce_bytes: [u8; 12] = nonce
        .try_into()
        .map_err(|_| anyhow::anyhow!("encrypted nonce must be 12 bytes"))?;
    let ciphertext = URL_SAFE_NO_PAD
        .decode(&sealed.ciphertext)
        .context("decode encrypted payload")?;
    let key: Key = (*secret).into();
    let cipher = ChaCha20Poly1305::new(&key);
    let nonce: Nonce = nonce_bytes.into();
    let plaintext = cipher
        .decrypt(
            &nonce,
            Payload {
                msg: &ciphertext,
                aad,
            },
        )
        .map_err(|_| anyhow::anyhow!("payload authentication failed"))?;
    serde_json::from_slice(&plaintext).context("decode encrypted JSON payload")
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
    fn payload_round_trip_and_aad_binding() {
        let secret = generate_secret();
        let machine = Uuid::new_v4();
        let client = Uuid::new_v4();
        let payload = ClientPayload::Request {
            request_id: Uuid::new_v4(),
            command: Command::ListSessions,
        };
        let aad = client_aad(machine, client);
        let sealed = seal(&secret, aad.as_bytes(), &payload).unwrap();
        let opened: ClientPayload = open(&secret, aad.as_bytes(), &sealed).unwrap();
        assert_eq!(opened, payload);

        let wrong_aad = client_aad(Uuid::new_v4(), client);
        assert!(open::<ClientPayload>(&secret, wrong_aad.as_bytes(), &sealed).is_err());
    }

    #[test]
    fn secret_round_trip() {
        let secret = generate_secret();
        assert_eq!(decode_secret(&encode_secret(&secret)).unwrap(), secret);
    }

    #[test]
    fn android_chacha20_poly1305_vector() {
        let secret: [u8; 32] = std::array::from_fn(|index| index as u8);
        let nonce_bytes: [u8; 12] = std::array::from_fn(|index| index as u8);
        let machine = Uuid::parse_str("01890f5e-b080-7cc0-98d2-a0f9d1f43c01").unwrap();
        let client = Uuid::parse_str("01890f5e-b080-7cc0-98d2-a0f9d1f43c02").unwrap();
        let stream_id = Uuid::parse_str("01890f5e-b080-7cc0-98d2-a0f9d1f43c03").unwrap();
        let payload = ClientPayload::TerminalInput {
            stream_id,
            data: "AAMD_w".into(),
        };
        let plaintext = serde_json::to_vec(&payload).unwrap();
        let cipher = ChaCha20Poly1305::new(&Key::from(secret));
        let ciphertext = cipher
            .encrypt(
                &Nonce::from(nonce_bytes),
                Payload {
                    msg: &plaintext,
                    aad: client_aad(machine, client).as_bytes(),
                },
            )
            .unwrap();

        assert_eq!(
            URL_SAFE_NO_PAD.encode(ciphertext),
            "8tl8eVlyh3qV91qB9XRgAqUv24khAdmbyrVcsQelx1Gz6UjR3DyBqQ8LRr5Q8aJBTryZWcpL26s_geFjomL_5h9B2PyDIB-K9CVvkDoYvYW7_H0QLiu9elEg0nOpvdMVodn4UW4hba2Zi6T7"
        );
    }
}
