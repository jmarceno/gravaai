#include "runtime.h"

#include <QCoreApplication>
#include <QDebug>
#include <QObject>

void gravaai_qt_quit() {
    if (auto *app = QCoreApplication::instance()) {
        app->quit();
    }
}

bool gravaai_qt_load_engine(QQmlApplicationEngine &engine, const QUrl &url) {
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreated,
        &engine,
        [&engine](QObject *object, const QUrl &createdUrl) {
            if (object != nullptr) {
                return;
            }
            qCritical() << "GravaAI: QML root failed to load:" << createdUrl;
            if (auto *app = QCoreApplication::instance()) {
                app->exit(70);
            }
            Q_UNUSED(engine);
        },
        Qt::DirectConnection);
    // The CXX-Qt module is embedded under the `:/qt/qml` resource prefix,
    // which only became a default QML import path in Qt 6.5. Without this,
    // Qt 6.4 (Ubuntu 24.04 / Pop!_OS) finds no module qmldir and every
    // component in qml/components and qml/pages is "not a type".
    engine.addImportPath(QStringLiteral("qrc:/qt/qml"));
    engine.load(url);
    if (engine.rootObjects().isEmpty()) {
        qCritical() << "GravaAI: QML engine produced no root object for" << url;
        if (auto *app = QCoreApplication::instance()) {
            app->exit(70);
        }
        return false;
    }
    return true;
}

void gravaai_qt_exit(int code) {
    if (auto *app = QCoreApplication::instance()) {
        app->exit(code);
    }
}
