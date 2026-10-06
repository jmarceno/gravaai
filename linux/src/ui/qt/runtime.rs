//! Small native Qt helpers which are intentionally kept outside QML.

#[cxx::bridge]
mod ffi {
    unsafe extern "C++" {
        include!("src/ui/qt/runtime.h");
        include!("cxx-qt-lib/qguiapplication.h");
        type QGuiApplication = cxx_qt_lib::QGuiApplication;
        include!("cxx-qt-lib/core/qvector/qvector_QByteArray.h");
        type QVector_QByteArray = cxx_qt_lib::QVector<cxx_qt_lib::QByteArray>;
        fn gravaai_qt_application(args: &QVector_QByteArray) -> UniquePtr<QGuiApplication>;

        include!("cxx-qt-lib/qqmlapplicationengine.h");
        type QQmlApplicationEngine = cxx_qt_lib::QQmlApplicationEngine;
        include!("cxx-qt-lib/qurl.h");
        type QUrl = cxx_qt_lib::QUrl;

        /// Quit the active Qt event loop on the Qt thread.
        fn gravaai_qt_quit();

        /// Load QML and fail fast when objectCreated reports a null root.
        fn gravaai_qt_load_engine(engine: Pin<&mut QQmlApplicationEngine>, url: &QUrl) -> bool;

        /// Exit the Qt event loop with a non-zero status on fatal bootstrap
        /// errors, rather than relying on a QML-only Qt.quit() call.
        fn gravaai_qt_exit(code: i32);
    }
}

pub fn request_quit() {
    ffi::gravaai_qt_quit();
}

pub fn load_engine(
    engine: std::pin::Pin<&mut cxx_qt_lib::QQmlApplicationEngine>,
    url: &cxx_qt_lib::QUrl,
) -> bool {
    ffi::gravaai_qt_load_engine(engine, url)
}

pub fn request_exit(code: i32) {
    ffi::gravaai_qt_exit(code);
}

/// Preserve argv for Qt's lifetime, including offscreen smoke arguments.
pub fn application() -> cxx::UniquePtr<cxx_qt_lib::QGuiApplication> {
    use std::os::unix::ffi::OsStrExt;
    let mut args = cxx_qt_lib::QVector::<cxx_qt_lib::QByteArray>::default();
    for arg in std::env::args_os() {
        args.append(cxx_qt_lib::QByteArray::from(arg.as_bytes()));
    }
    ffi::gravaai_qt_application(&args)
}
