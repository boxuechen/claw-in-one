mod capabilities;
mod config;
mod daemon;
mod install;
mod protocol;
mod setup;
mod toolchains;

use std::env;
use std::path::PathBuf;

use config::Config;
use daemon::Daemon;

fn main() {
    if let Err(error) = run() {
        eprintln!("ClawInOne Supervisor: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut arguments = env::args_os().skip(1);
    if arguments.next().as_deref() != Some(std::ffi::OsStr::new("serve"))
        || arguments.next().as_deref() != Some(std::ffi::OsStr::new("--config"))
    {
        return Err("usage: claw-in-one-supervisor serve --config <absolute-path>".into());
    }
    let config_path = arguments
        .next()
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .ok_or("config path must be absolute")?;
    if arguments.next().is_some() {
        return Err("unexpected argument".into());
    }
    Daemon::new(Config::load(&config_path)?)?.run()
}
