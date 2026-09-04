// GravaAi — single binary, dispatched by CLI flag (see core::run_mode).
//
// Single binary, dispatched by CLI flag (see core::run_mode):
//   --daemon    GTK-free background daemon (engine + tray + D-Bus service)
//   --window    GTK/libadwaita window child (spawned by the daemon)
//   --process   one-shot AI processing child (audio transcript notes)
//   --install   one-shot model/engine install child (spec json)
//   --uninstall remove everything the app installed or created, then exit
//   (no flag)   client mode: ensure the daemon runs, then open a window.

mod audio;
mod client;
mod config;
mod core;
mod daemon;
mod detection;
mod processing;
mod services;
mod utils;

mod ui;

fn main() {
    let argv: Vec<String> = std::env::args().collect();
    let mode = core::run_mode::resolve_run_mode(&argv);
    match mode {
        core::run_mode::RunMode::Daemon => daemon::app::run_daemon(),
        core::run_mode::RunMode::Window => {
            #[cfg(feature = "ui")]
            ui::window_app::run_window();
            #[cfg(not(feature = "ui"))]
            {
                eprintln!("window UI not compiled in (feature \"ui\" disabled)");
                std::process::exit(1);
            }
        }
        core::run_mode::RunMode::Process => {
            // --process <audio> <transcript> <notes>
            let args: Vec<String> = argv
                .iter()
                .skip(1)
                .filter(|a| *a != "--process")
                .cloned()
                .collect();
            std::process::exit(daemon::processor::run_processor_child(&args));
        }
        core::run_mode::RunMode::Install => {
            // --install <spec-json>
            let args: Vec<String> = argv
                .iter()
                .skip(1)
                .filter(|a| *a != "--install")
                .cloned()
                .collect();
            std::process::exit(daemon::installer::run_install_child(
                args.first().map(|s| s.as_str()).unwrap_or(""),
            ));
        }
        core::run_mode::RunMode::Uninstall => {
            std::process::exit(crate::utils::self_uninstall::run_uninstall());
        }
        core::run_mode::RunMode::Client => client::run_client(),
    }
}
