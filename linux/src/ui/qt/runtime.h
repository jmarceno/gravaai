#pragma once

#include <QtQml/QQmlApplicationEngine>
#include <QtCore/QUrl>

void gravaai_qt_quit();
bool gravaai_qt_load_engine(QQmlApplicationEngine &engine, const QUrl &url);
void gravaai_qt_exit(int code);
