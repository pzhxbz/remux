use std::{
    path::{Path, PathBuf},
    process::{Command as ProcessCommand, Output},
};

use anyhow::{Context, Result, bail};
use remux_protocol::{PaneInfo, SessionInfo, WindowInfo};

const SESSION_FORMAT: &str = "#{session_id}\t#{session_name}\t#{session_windows}\t#{session_attached}\t#{session_created}\t#{session_activity}";
const WINDOW_FORMAT: &str = "#{window_id}\t#{window_index}\t#{window_name}\t#{window_active}\t#{window_panes}\t#{window_layout}";
const PANE_FORMAT: &str = "#{pane_id}\t#{pane_index}\t#{pane_active}\t#{pane_current_command}\t#{pane_current_path}\t#{pane_width}\t#{pane_height}";

#[derive(Debug, Clone)]
pub struct Tmux {
    binary: String,
}

impl Tmux {
    pub fn new(binary: String) -> Self {
        Self { binary }
    }
    pub fn binary(&self) -> &str {
        &self.binary
    }

    pub fn version(&self) -> Result<String> {
        let output = self.run(["-V"])?;
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_owned())
    }

    pub fn list_sessions(&self) -> Result<Vec<SessionInfo>> {
        let Some(output) = self.run_allow_no_server(["list-sessions", "-F", SESSION_FORMAT])?
        else {
            return Ok(Vec::new());
        };
        parse_lines(&output.stdout, parse_session)
    }

    pub fn list_windows(&self, session_id: &str) -> Result<Vec<WindowInfo>> {
        validate_id(session_id, '$', "session")?;
        let output = self.run(["list-windows", "-t", session_id, "-F", WINDOW_FORMAT])?;
        parse_lines(&output.stdout, parse_window)
    }

    pub fn list_panes(&self, window_id: &str) -> Result<Vec<PaneInfo>> {
        validate_id(window_id, '@', "window")?;
        let output = self.run(["list-panes", "-t", window_id, "-F", PANE_FORMAT])?;
        parse_lines(&output.stdout, parse_pane)
    }

    pub fn create_session(&self, name: &str, cwd: Option<&str>) -> Result<SessionInfo> {
        validate_name(name)?;
        let cwd = validate_cwd(cwd)?;
        let mut args = vec!["new-session", "-d", "-P", "-F", SESSION_FORMAT, "-s", name];
        let cwd_string;
        if let Some(path) = cwd {
            cwd_string = path.to_string_lossy().into_owned();
            args.extend(["-c", cwd_string.as_str()]);
        }
        let output = self.run(args)?;
        parse_single(&output.stdout, parse_session)
    }

    pub fn rename_session(&self, session_id: &str, new_name: &str) -> Result<()> {
        validate_id(session_id, '$', "session")?;
        validate_name(new_name)?;
        self.run(["rename-session", "-t", session_id, new_name])?;
        Ok(())
    }

    pub fn kill_session(&self, session_id: &str) -> Result<()> {
        validate_id(session_id, '$', "session")?;
        self.run(["kill-session", "-t", session_id])?;
        Ok(())
    }

    pub fn create_window(
        &self,
        session_id: &str,
        name: Option<&str>,
        cwd: Option<&str>,
    ) -> Result<WindowInfo> {
        validate_id(session_id, '$', "session")?;
        if let Some(name) = name {
            validate_name(name)?;
        }
        let cwd = validate_cwd(cwd)?;
        let mut args = vec![
            "new-window",
            "-d",
            "-P",
            "-F",
            WINDOW_FORMAT,
            "-t",
            session_id,
        ];
        let cwd_string;
        if let Some(name) = name {
            args.extend(["-n", name]);
        }
        if let Some(path) = cwd {
            cwd_string = path.to_string_lossy().into_owned();
            args.extend(["-c", cwd_string.as_str()]);
        }
        let output = self.run(args)?;
        parse_single(&output.stdout, parse_window)
    }

    pub fn rename_window(&self, window_id: &str, new_name: &str) -> Result<()> {
        validate_id(window_id, '@', "window")?;
        validate_name(new_name)?;
        self.run(["rename-window", "-t", window_id, new_name])?;
        Ok(())
    }

    pub fn kill_window(&self, window_id: &str) -> Result<()> {
        validate_id(window_id, '@', "window")?;
        self.run(["kill-window", "-t", window_id])?;
        Ok(())
    }

    pub fn has_attached_clients(&self, session_id: &str) -> Result<bool> {
        validate_id(session_id, '$', "session")?;
        let output = self.run(["list-clients", "-t", session_id, "-F", "#{client_pid}"])?;
        Ok(!String::from_utf8_lossy(&output.stdout).trim().is_empty())
    }

    pub fn detach_client(&self, client_name: &Path) -> Result<()> {
        let output = ProcessCommand::new(&self.binary)
            .args(["detach-client", "-t"])
            .arg(client_name)
            .output()
            .with_context(|| format!("execute {}", self.binary))?;
        if output.status.success() {
            return Ok(());
        }
        let error = String::from_utf8_lossy(&output.stderr);
        if error.contains("can't find client")
            || error.contains("no current client")
            || error.contains("no server running")
            || error.contains("No such file or directory")
        {
            return Ok(());
        }
        bail!("tmux failed: {}", error.trim())
    }

    pub fn refresh_client(&self, client_name: &Path) -> Result<()> {
        let output = ProcessCommand::new(&self.binary)
            .args(["refresh-client", "-t"])
            .arg(client_name)
            .output()
            .with_context(|| format!("execute {}", self.binary))?;
        if output.status.success() {
            return Ok(());
        }
        bail!(
            "tmux failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )
    }

    pub fn select_client_window(&self, client_name: &Path, window_id: &str) -> Result<()> {
        validate_id(window_id, '@', "window")?;
        let output = ProcessCommand::new(&self.binary)
            .args(["switch-client", "-c"])
            .arg(client_name)
            .args(["-t", window_id])
            .output()
            .with_context(|| format!("execute {}", self.binary))?;
        if output.status.success() {
            return Ok(());
        }
        bail!(
            "tmux failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )
    }

    fn run<I, S>(&self, args: I) -> Result<Output>
    where
        I: IntoIterator<Item = S>,
        S: AsRef<std::ffi::OsStr>,
    {
        let output = ProcessCommand::new(&self.binary)
            .args(args)
            .output()
            .with_context(|| format!("execute {}", self.binary))?;
        if !output.status.success() {
            bail!(
                "tmux failed: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        Ok(output)
    }

    fn run_allow_no_server<I, S>(&self, args: I) -> Result<Option<Output>>
    where
        I: IntoIterator<Item = S>,
        S: AsRef<std::ffi::OsStr>,
    {
        let output = ProcessCommand::new(&self.binary)
            .args(args)
            .output()
            .with_context(|| format!("execute {}", self.binary))?;
        if output.status.success() {
            return Ok(Some(output));
        }
        let error = String::from_utf8_lossy(&output.stderr);
        if error.contains("no server running") || error.contains("No such file or directory") {
            return Ok(None);
        }
        bail!("tmux failed: {}", error.trim())
    }
}

fn validate_id(value: &str, prefix: char, kind: &str) -> Result<()> {
    let digits = value.strip_prefix(prefix).unwrap_or("");
    anyhow::ensure!(
        !digits.is_empty() && digits.chars().all(|ch| ch.is_ascii_digit()),
        "invalid {kind} id"
    );
    Ok(())
}

fn validate_name(name: &str) -> Result<()> {
    anyhow::ensure!(!name.trim().is_empty(), "tmux name cannot be empty");
    anyhow::ensure!(name.len() <= 128, "tmux name is too long");
    anyhow::ensure!(
        !name.chars().any(char::is_control),
        "tmux name cannot contain control characters"
    );
    Ok(())
}

fn validate_cwd(cwd: Option<&str>) -> Result<Option<PathBuf>> {
    let Some(cwd) = cwd else { return Ok(None) };
    let path = std::fs::canonicalize(cwd).with_context(|| format!("resolve cwd {cwd}"))?;
    anyhow::ensure!(path.is_dir(), "cwd is not a directory");
    Ok(Some(path))
}

fn parse_lines<T>(bytes: &[u8], parser: fn(&str) -> Result<T>) -> Result<Vec<T>> {
    let text = String::from_utf8(bytes.to_vec()).context("tmux output is not UTF-8")?;
    text.lines()
        .filter(|line| !line.is_empty())
        .map(parser)
        .collect()
}

fn parse_single<T>(bytes: &[u8], parser: fn(&str) -> Result<T>) -> Result<T> {
    let text = String::from_utf8(bytes.to_vec()).context("tmux output is not UTF-8")?;
    parser(text.trim_end())
}

fn parse_session(line: &str) -> Result<SessionInfo> {
    let fields: Vec<_> = line.splitn(6, '\t').collect();
    anyhow::ensure!(fields.len() == 6, "invalid tmux session record");
    Ok(SessionInfo {
        id: fields[0].into(),
        name: fields[1].into(),
        windows: fields[2].parse().context("invalid session window count")?,
        attached_clients: fields[3].parse().context("invalid attached client count")?,
        created_at: fields[4].parse().context("invalid session created time")?,
        activity_at: fields[5].parse().context("invalid session activity time")?,
    })
}

fn parse_window(line: &str) -> Result<WindowInfo> {
    let fields: Vec<_> = line.splitn(6, '\t').collect();
    anyhow::ensure!(fields.len() == 6, "invalid tmux window record");
    Ok(WindowInfo {
        id: fields[0].into(),
        index: fields[1].parse().context("invalid window index")?,
        name: fields[2].into(),
        active: fields[3] == "1",
        panes: fields[4].parse().context("invalid pane count")?,
        layout: fields[5].into(),
    })
}

fn parse_pane(line: &str) -> Result<PaneInfo> {
    let fields: Vec<_> = line.splitn(7, '\t').collect();
    anyhow::ensure!(fields.len() == 7, "invalid tmux pane record");
    Ok(PaneInfo {
        id: fields[0].into(),
        index: fields[1].parse().context("invalid pane index")?,
        active: fields[2] == "1",
        command: fields[3].into(),
        current_path: fields[4].into(),
        width: fields[5].parse().context("invalid pane width")?,
        height: fields[6].parse().context("invalid pane height")?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_ids_without_accepting_targets() {
        assert!(validate_id("$12", '$', "session").is_ok());
        assert!(validate_id("work", '$', "session").is_err());
        assert!(validate_id("$1:0", '$', "session").is_err());
    }

    #[test]
    fn parses_session_record() {
        let session = parse_session("$1\twork\t2\t0\t10\t11").unwrap();
        assert_eq!(session.name, "work");
        assert_eq!(session.windows, 2);
    }
}
