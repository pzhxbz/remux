use std::{
    fs,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result};
use remux_protocol::{PROTOCOL_VERSION, PairingBundle, encode_secret, generate_secret};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentConfig {
    pub version: u16,
    pub relay_url: String,
    pub relay_token: String,
    pub machine_id: Uuid,
    pub machine_name: String,
    pub machine_secret: String,
    pub tmux_binary: String,
}

impl AgentConfig {
    pub fn new(
        relay_url: String,
        relay_token: String,
        machine_name: String,
        tmux_binary: String,
    ) -> Self {
        Self {
            version: PROTOCOL_VERSION,
            relay_url,
            relay_token,
            machine_id: Uuid::new_v4(),
            machine_name,
            machine_secret: encode_secret(&generate_secret()),
            tmux_binary,
        }
    }

    pub fn load(path: &Path) -> Result<Self> {
        let text = fs::read_to_string(path)
            .with_context(|| format!("read TOML config {}", path.display()))?;
        let config: Self = toml::from_str(&text).context("decode agent TOML config")?;
        anyhow::ensure!(
            config.version == PROTOCOL_VERSION,
            "unsupported agent config version"
        );
        Ok(config)
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(parent) = path.parent().filter(|path| !path.as_os_str().is_empty()) {
            fs::create_dir_all(parent).with_context(|| format!("create {}", parent.display()))?;
        }
        let text = toml::to_string_pretty(self).context("encode agent TOML config")?;
        write_private(path, text.as_bytes())
    }

    pub fn pairing_bundle(&self) -> PairingBundle {
        PairingBundle {
            version: self.version,
            relay_url: self.relay_url.clone(),
            machine_id: self.machine_id,
            machine_name: self.machine_name.clone(),
            machine_secret: self.machine_secret.clone(),
        }
    }

    pub fn save_pairing(&self, path: &Path) -> Result<()> {
        let text =
            toml::to_string_pretty(&self.pairing_bundle()).context("encode pairing TOML bundle")?;
        write_private(path, text.as_bytes())
    }
}

fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    #[cfg(unix)]
    {
        use std::{io::Write, os::unix::fs::OpenOptionsExt};
        let mut options = fs::OpenOptions::new();
        options.write(true).create(true).truncate(true).mode(0o600);
        let mut file = options
            .open(path)
            .with_context(|| format!("open {}", path.display()))?;
        file.write_all(bytes)
            .with_context(|| format!("write {}", path.display()))?;
        let mut permissions = file.metadata()?.permissions();
        use std::os::unix::fs::PermissionsExt;
        permissions.set_mode(0o600);
        file.set_permissions(permissions)?;
        file.sync_all()?;
        Ok(())
    }

    #[cfg(not(unix))]
    {
        fs::write(path, bytes).with_context(|| format!("write {}", path.display()))
    }
}

pub fn default_machine_name() -> String {
    hostname::get()
        .ok()
        .and_then(|value| value.into_string().ok())
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "unknown-machine".into())
}

pub fn default_config_path() -> PathBuf {
    #[cfg(target_os = "macos")]
    if let Some(home) = std::env::var_os("HOME") {
        return PathBuf::from(home)
            .join("Library")
            .join("Application Support")
            .join("RemoteMux")
            .join("agent.toml");
    }

    #[cfg(not(target_os = "macos"))]
    {
        if let Some(config_home) = std::env::var_os("XDG_CONFIG_HOME") {
            return PathBuf::from(config_home).join("remux").join("agent.toml");
        }
        if let Some(home) = std::env::var_os("HOME") {
            return PathBuf::from(home)
                .join(".config")
                .join("remux")
                .join("agent.toml");
        }
    }

    PathBuf::from("agent.toml")
}

pub fn default_pairing_path(config_path: &Path) -> PathBuf {
    config_path.with_file_name("pairing.toml")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_and_pairing_are_toml() {
        let config = AgentConfig::new(
            "wss://relay.example.test".into(),
            "agent-token-0123456789".into(),
            "linux-one".into(),
            "/usr/bin/tmux".into(),
        );
        let encoded = toml::to_string_pretty(&config).unwrap();
        let decoded: AgentConfig = toml::from_str(&encoded).unwrap();
        assert_eq!(decoded.machine_id, config.machine_id);
        assert_eq!(decoded.relay_url, config.relay_url);

        let pairing = toml::to_string_pretty(&config.pairing_bundle()).unwrap();
        let decoded_pairing: PairingBundle = toml::from_str(&pairing).unwrap();
        assert_eq!(decoded_pairing.machine_secret, config.machine_secret);
    }
}
