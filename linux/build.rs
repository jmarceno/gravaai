use cxx_qt_build::{CxxQtBuilder, QmlFile, QmlModule};

fn main() {
    // The daemon/core binary is intentionally built without Qt. Cargo still
    // executes build.rs for every target, so only generate the CXX-Qt bridge
    // when the companion UI feature is enabled.
    if std::env::var_os("CARGO_FEATURE_UI").is_none() {
        return;
    }
    CxxQtBuilder::new_qml_module(QmlModule::new("io.github.jmarceno.gravaai").qml_files([
        QmlFile::from("qml/Main.qml"),
        QmlFile::from("qml/Theme.qml").singleton(true),
        QmlFile::from("qml/AppButton.qml"),
        QmlFile::from("qml/AppCard.qml"),
        QmlFile::from("qml/AppShell.qml"),
        QmlFile::from("qml/AppCheckBox.qml"),
        QmlFile::from("qml/AppComboBox.qml"),
        QmlFile::from("qml/AppProgressBar.qml"),
        QmlFile::from("qml/AudioLevelMeter.qml"),
        QmlFile::from("qml/AppField.qml"),
        QmlFile::from("qml/AppSwitch.qml"),
        QmlFile::from("qml/StatusBadge.qml"),
        QmlFile::from("qml/RecordingPill.qml"),
        QmlFile::from("qml/SidebarItem.qml"),
        QmlFile::from("qml/TitleBar.qml"),
        QmlFile::from("qml/RecorderPage.qml"),
        QmlFile::from("qml/LibraryPage.qml"),
        QmlFile::from("qml/JobsPage.qml"),
        QmlFile::from("qml/ModelsPage.qml"),
        QmlFile::from("qml/DownloadsPage.qml"),
        QmlFile::from("qml/PromptsPage.qml"),
        QmlFile::from("qml/GeneralPage.qml"),
        // Test-only scene used by the offscreen geometry/contract smoke gate.
        QmlFile::from("qml/SmokeHarness.qml"),
    ]))
    .files(["src/ui/qt/controller.rs", "src/ui/qt/runtime.rs"])
    .cpp_file("src/ui/qt/runtime.cpp")
    .qt_module("Network")
    .qt_module("Quick")
    .qt_module("QuickControls2")
    .qt_module("Widgets")
    .qrc_resources(["assets/icons/hicolor/scalable/apps/gravaai.svg"])
    .build();
}
