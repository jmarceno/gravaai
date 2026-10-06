#pragma once

#include <QtQml/QQmlApplicationEngine>
#include <QtCore/QUrl>
#include <QtGui/QGuiApplication>
#include <QtCore/QByteArray>
#include <QtCore/QVector>
#include <memory>

std::unique_ptr<QGuiApplication> gravaai_qt_application(const QVector<QByteArray> &args);

void gravaai_qt_quit();
bool gravaai_qt_load_engine(QQmlApplicationEngine &engine, const QUrl &url);
void gravaai_qt_exit(int code);
