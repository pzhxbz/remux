use std::{
    collections::HashMap,
    io::{Read, Write},
    path::PathBuf,
    sync::{Arc, Mutex},
};

use anyhow::{Context, Result};
use portable_pty::{CommandBuilder, MasterPty, PtySize, native_pty_system};
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
    session_id: String,
    client_tty: PathBuf,
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
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
        let client_tty = pty.master.tty_name().context("resolve PTY name")?;
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
        let stream_id = Uuid::new_v4();

        self.handles.lock().unwrap().insert(
            stream_id,
            TerminalHandle {
                client_id,
                session_id: session_id.to_owned(),
                client_tty,
                master: pty.master,
                writer,
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

    pub fn refresh(&self, client_id: Uuid, stream_id: Uuid) -> Result<()> {
        let client_tty = {
            let handles = self.handles.lock().unwrap();
            let handle = handles
                .get(&stream_id)
                .context("terminal stream not found")?;
            anyhow::ensure!(
                handle.client_id == client_id,
                "terminal stream belongs to another client"
            );
            handle.client_tty.clone()
        };
        self.tmux
            .refresh_client(&client_tty)
            .context("refresh tmux client")
    }

    pub fn select_window(&self, client_id: Uuid, stream_id: Uuid, window_id: &str) -> Result<()> {
        let (session_id, client_tty) = {
            let handles = self.handles.lock().unwrap();
            let handle = handles
                .get(&stream_id)
                .context("terminal stream not found")?;
            anyhow::ensure!(
                handle.client_id == client_id,
                "terminal stream belongs to another client"
            );
            (handle.session_id.clone(), handle.client_tty.clone())
        };
        anyhow::ensure!(
            self.tmux
                .list_windows(&session_id)?
                .iter()
                .any(|window| window.id == window_id),
            "window does not belong to the attached session"
        );
        self.tmux
            .select_client_window(&client_tty, window_id)
            .context("select tmux client window")
    }

    pub fn detach(&self, client_id: Uuid, stream_id: Uuid) -> Result<()> {
        let client_tty = {
            let handles = self.handles.lock().unwrap();
            let handle = handles
                .get(&stream_id)
                .context("terminal stream not found")?;
            anyhow::ensure!(
                handle.client_id == client_id,
                "terminal stream belongs to another client"
            );
            handle.client_tty.clone()
        };
        self.tmux
            .detach_client(&client_tty)
            .context("gracefully detach tmux client")?;
        self.handles.lock().unwrap().remove(&stream_id);
        Ok(())
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
        let handles: Vec<_> = self
            .handles
            .lock()
            .unwrap()
            .iter()
            .map(|(stream_id, handle)| (*stream_id, handle.client_id))
            .collect();
        for (stream_id, client_id) in handles {
            let _ = self.detach(client_id, stream_id);
        }
    }
}

#[cfg(all(test, unix))]
mod tests {
    use std::{
        fs, os::unix::fs::PermissionsExt, path::PathBuf, process::Command as ProcessCommand,
        thread, time::Duration,
    };

    use super::*;

    struct IsolatedTmux {
        directory: PathBuf,
        wrapper: PathBuf,
        tmux: Tmux,
    }

    impl IsolatedTmux {
        fn create() -> Option<Self> {
            if ProcessCommand::new("tmux").arg("-V").output().is_err() {
                return None;
            }
            let socket_name = format!("remux-test-{}", Uuid::new_v4().simple());
            let directory = std::env::temp_dir().join(&socket_name);
            fs::create_dir(&directory).unwrap();
            let wrapper = directory.join("tmux");
            fs::write(
                &wrapper,
                format!("#!/bin/sh\nexec tmux -L {socket_name} \"$@\"\n"),
            )
            .unwrap();
            fs::set_permissions(&wrapper, fs::Permissions::from_mode(0o700)).unwrap();
            let tmux = Tmux::new(wrapper.to_string_lossy().into_owned());
            Some(Self {
                directory,
                wrapper,
                tmux,
            })
        }
    }

    impl Drop for IsolatedTmux {
        fn drop(&mut self) {
            let _ = ProcessCommand::new(&self.wrapper)
                .arg("kill-server")
                .output();
            let _ = fs::remove_dir_all(&self.directory);
        }
    }

    fn wait_until(mut predicate: impl FnMut() -> bool) -> bool {
        for _ in 0..200 {
            if predicate() {
                return true;
            }
            thread::sleep(Duration::from_millis(10));
        }
        false
    }

    #[test]
    fn graceful_detach_preserves_the_tmux_session() {
        let Some(fixture) = IsolatedTmux::create() else {
            eprintln!("tmux is unavailable; skipping integration assertion");
            return;
        };
        let session = fixture
            .tmux
            .create_session("detach-survives", None)
            .unwrap();
        let (events, _receiver) = mpsc::channel(64);
        let manager = TerminalManager::new(fixture.tmux.clone(), events);
        let client_id = Uuid::new_v4();
        let (stream_id, _) = manager
            .open(client_id, &session.id, 80, 24, SizePolicy::Auto)
            .unwrap();

        assert!(wait_until(|| fixture
            .tmux
            .list_sessions()
            .unwrap()
            .iter()
            .any(
                |candidate| candidate.id == session.id && candidate.attached_clients == 1
            )));

        let foreign_session = fixture.tmux.create_session("foreign", None).unwrap();
        let foreign_window = fixture
            .tmux
            .list_windows(&foreign_session.id)
            .unwrap()
            .remove(0);
        assert!(
            manager
                .select_window(client_id, stream_id, &foreign_window.id)
                .is_err()
        );

        let second = fixture
            .tmux
            .create_window(&session.id, Some("second"), None)
            .unwrap();
        manager
            .select_window(client_id, stream_id, &second.id)
            .unwrap();
        assert!(wait_until(|| fixture
            .tmux
            .list_windows(&session.id)
            .unwrap()
            .iter()
            .any(|window| window.id == second.id && window.active)));

        manager.detach(client_id, stream_id).unwrap();

        assert!(wait_until(|| fixture
            .tmux
            .list_sessions()
            .unwrap()
            .iter()
            .any(
                |candidate| candidate.id == session.id && candidate.attached_clients == 0
            )));
    }
}
