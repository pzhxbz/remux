use std::{
    collections::HashMap,
    io::{Read, Write},
    sync::{Arc, Mutex},
};

use anyhow::{Context, Result};
use portable_pty::{ChildKiller, CommandBuilder, MasterPty, PtySize, native_pty_system};
use remux_protocol::{AgentPayload, MAX_TERMINAL_CHUNK_BYTES, SizePolicy, terminal_bytes_to_text};
use tokio::sync::mpsc;
use uuid::Uuid;

use crate::tmux::Tmux;

#[derive(Debug)]
pub enum AgentEvent {
    Payload {
        client_id: Uuid,
        payload: AgentPayload,
    },
    Exited {
        client_id: Uuid,
        stream_id: Uuid,
        exit_code: Option<u32>,
    },
}

struct TerminalHandle {
    client_id: Uuid,
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    killer: Box<dyn ChildKiller + Send + Sync>,
}

#[derive(Clone)]
pub struct TerminalManager {
    tmux: Tmux,
    handles: Arc<Mutex<HashMap<Uuid, TerminalHandle>>>,
    events: mpsc::Sender<AgentEvent>,
}

impl TerminalManager {
    pub fn new(tmux: Tmux, events: mpsc::Sender<AgentEvent>) -> Self {
        Self {
            tmux,
            handles: Arc::new(Mutex::new(HashMap::new())),
            events,
        }
    }

    pub fn tmux(&self) -> &Tmux {
        &self.tmux
    }

    pub fn open(
        &self,
        client_id: Uuid,
        session_id: &str,
        cols: u16,
        rows: u16,
        policy: SizePolicy,
    ) -> Result<(Uuid, bool)> {
        anyhow::ensure!((20..=1000).contains(&cols), "terminal columns out of range");
        anyhow::ensure!((5..=500).contains(&rows), "terminal rows out of range");
        let existing_clients = self.tmux.has_attached_clients(session_id)?;
        let ignore_size = match policy {
            SizePolicy::Auto => existing_clients,
            SizePolicy::PreserveExisting => true,
            SizePolicy::TakeControl => false,
        };

        let pty = native_pty_system()
            .openpty(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .context("create PTY")?;
        let mut command = CommandBuilder::new(self.tmux.binary());
        command.arg("-u");
        command.arg("attach-session");
        if ignore_size {
            command.arg("-f");
            command.arg("ignore-size");
        }
        command.arg("-t");
        command.arg(session_id);
        command.env("TERM", "xterm-256color");
        command.env("COLORTERM", "truecolor");
        command.env_remove("TMUX");
        command.env_remove("TMUX_PANE");

        let mut child = pty
            .slave
            .spawn_command(command)
            .context("spawn tmux attach client")?;
        drop(pty.slave);
        let mut reader = pty.master.try_clone_reader().context("clone PTY reader")?;
        let writer = pty.master.take_writer().context("take PTY writer")?;
        let killer = child.clone_killer();
        let stream_id = Uuid::new_v4();

        self.handles.lock().unwrap().insert(
            stream_id,
            TerminalHandle {
                client_id,
                master: pty.master,
                writer,
                killer,
            },
        );

        let output_events = self.events.clone();
        std::thread::Builder::new()
            .name(format!("remux-pty-read-{stream_id}"))
            .spawn(move || {
                let mut buffer = vec![0_u8; MAX_TERMINAL_CHUNK_BYTES];
                loop {
                    match reader.read(&mut buffer) {
                        Ok(0) | Err(_) => break,
                        Ok(read) => {
                            let payload = AgentPayload::TerminalOutput {
                                stream_id,
                                data: terminal_bytes_to_text(&buffer[..read]),
                            };
                            if output_events
                                .blocking_send(AgentEvent::Payload { client_id, payload })
                                .is_err()
                            {
                                break;
                            }
                        }
                    }
                }
            })
            .context("spawn PTY reader")?;

        let exit_events = self.events.clone();
        std::thread::Builder::new()
            .name(format!("remux-pty-wait-{stream_id}"))
            .spawn(move || {
                let exit_code = child.wait().ok().map(|status| status.exit_code());
                let _ = exit_events.blocking_send(AgentEvent::Exited {
                    client_id,
                    stream_id,
                    exit_code,
                });
            })
            .context("spawn PTY waiter")?;

        Ok((stream_id, ignore_size))
    }

    pub fn input(&self, client_id: Uuid, stream_id: Uuid, bytes: &[u8]) -> Result<()> {
        anyhow::ensure!(
            bytes.len() <= MAX_TERMINAL_CHUNK_BYTES,
            "terminal input is too large"
        );
        let mut handles = self.handles.lock().unwrap();
        let handle = handles
            .get_mut(&stream_id)
            .context("terminal stream not found")?;
        anyhow::ensure!(
            handle.client_id == client_id,
            "terminal stream belongs to another client"
        );
        handle
            .writer
            .write_all(bytes)
            .context("write terminal input")?;
        handle.writer.flush().context("flush terminal input")
    }

    pub fn resize(&self, client_id: Uuid, stream_id: Uuid, cols: u16, rows: u16) -> Result<()> {
        anyhow::ensure!((20..=1000).contains(&cols), "terminal columns out of range");
        anyhow::ensure!((5..=500).contains(&rows), "terminal rows out of range");
        let handles = self.handles.lock().unwrap();
        let handle = handles
            .get(&stream_id)
            .context("terminal stream not found")?;
        anyhow::ensure!(
            handle.client_id == client_id,
            "terminal stream belongs to another client"
        );
        handle
            .master
            .resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .context("resize PTY")
    }

    pub fn detach(&self, client_id: Uuid, stream_id: Uuid) -> Result<()> {
        let mut handles = self.handles.lock().unwrap();
        let belongs = handles
            .get(&stream_id)
            .context("terminal stream not found")?
            .client_id
            == client_id;
        anyhow::ensure!(belongs, "terminal stream belongs to another client");
        let mut handle = handles.remove(&stream_id).unwrap();
        drop(handles);
        handle.killer.kill().context("terminate tmux attach client")
    }

    pub fn cleanup(&self, stream_id: Uuid) {
        self.handles.lock().unwrap().remove(&stream_id);
    }

    pub fn detach_client(&self, client_id: Uuid) {
        let stream_ids: Vec<_> = self
            .handles
            .lock()
            .unwrap()
            .iter()
            .filter_map(|(id, handle)| (handle.client_id == client_id).then_some(*id))
            .collect();
        for stream_id in stream_ids {
            let _ = self.detach(client_id, stream_id);
        }
    }

    pub fn detach_all(&self) {
        let handles = std::mem::take(&mut *self.handles.lock().unwrap());
        for (_, mut handle) in handles {
            let _ = handle.killer.kill();
        }
    }
}
