import 'dart:async';
import 'dart:io';

import 'package:cobble/domain/apps/default_apps.dart';
import 'package:cobble/domain/db/dao/active_notification_dao.dart';
import 'package:cobble/domain/db/dao/app_dao.dart';
import 'package:cobble/domain/db/dao/locker_cache_dao.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cobble/domain/db/dao/notification_channel_dao.dart';
import 'package:path/path.dart';
import 'package:sqflite/sqflite.dart';

import 'dao/timeline_pin_dao.dart';

Future<void> createTimelinePinsTable(Database db) async {
  await db.execute("""
    CREATE TABLE $tableTimelinePins(
      itemId TEXT PRIMARY KEY NOT NULL,
      parentId TEXT NOT NULL,
      backingId TEXT,
      timestamp INT NOT NULL,
      duration INT NOT NULL,
      type TEXT NOT NULL,
      isVisible INTEGER NOT NULL,
      isFloating INTEGER NOT NULL,
      isAllDay INTEGER NOT NULL,
      persistQuickView INTEGER NOT NULL,
      layout TEXT NOT NULL,
      attributesJson TEXT NOT NULL,
      actionsJson TEXT,
      nextSyncAction TEXT NOT NULL
    )
  """);
}

Future<void> createActiveNotificationsTable(Database db) async {
  await db.execute("""
    CREATE TABLE $tableActiveNotifications(
      pinId TEXT PRIMARY KEY NOT NULL,
      notifId INT NOT NULL,
      packageId TEXT NOT NULL,
      tagId TEXT NOT NULL
    )
  """);
  await db.execute("""
    CREATE TABLE $tableChannels(
      channelId TEXT NOT NULL,
      packageId TEXT NOT NULL,
      name TEXT,
      description TEXT,
      shouldNotify INTEGER NOT NULL,
      UNIQUE(channelId, packageId)
    )
  """);
}

Future<void> createAppsTable(Database db) async {
  await db.execute("""
    CREATE TABLE $tableApps(
      uuid TEXT PRIMARY KEY NOT NULL,
      shortName TEXT NOT NULL,
      longName TEXT NOT NULL,
      isSystem INTEGER NOT NULL DEFAULT 0,
      isWatchface INTEGER NOT NULL,
      company TEXT NOT NULL,
      appstoreId TEXT,
      version TEXT NOT NULL,
      appOrder INTEGER NOT NULL,
      supportedHardware TEXT NOT NULL,
      url TEXT,
      processInfoFlags TEXT NOT NULL,
      sdkVersions TEXT NOT NULL,
      nextSyncAction TEXT NOT NULL
    )
  """);

  final appDao = AppDao(Future.value(db));

  await populate_system_apps(appDao);
}

Future<void> createLockerCacheTable(Database db) async {
  await db.execute("""
    CREATE TABLE $tableLocker(
      id TEXT PRIMARY KEY NOT NULL,
      uuid TEXT NOT NULL,
      version TEXT NOT NULL,
      apliteIcon TEXT,
      basaltIcon TEXT,
      chalkIcon TEXT,
      dioriteIcon TEXT,
      apliteList TEXT,
      basaltList TEXT,
      chalkList TEXT,
      dioriteList TEXT,
      markedForDeletion INTEGER NOT NULL
    )
  """);
}

void _createDb(Database db) async {
  await createTimelinePinsTable(db);
  await createActiveNotificationsTable(db);
  await createAppsTable(db);
  await createLockerCacheTable(db);
}

void _upgradeDb(Database db, int oldVersion, int newVersion) async {
  if (oldVersion < 2) {
    await db.execute("UPDATE $tableTimelinePins SET type = lower(type)");
    await db.execute("UPDATE $tableTimelinePins "
        "SET layout = 'calendarPin' "
        "WHERE layout = 'CALENDAR_PIN'");
  }

  if (oldVersion < 3) {
    createActiveNotificationsTable(db);
    createAppsTable(db);
  }

  if (oldVersion < 4) {
    await db.execute(
        "ALTER TABLE $tableApps ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0;");

    final appDao = AppDao(Future.value(db));
    await populate_system_apps(appDao);
  }

  if (oldVersion < 5) {
    final appDao = AppDao(Future.value(db));
    await appDao.fixAppOrdering();

    await db.execute("UPDATE $tableApps SET "
        "appOrder = -1 WHERE "
        "isWatchface = 1");
  }
  if (oldVersion < 6) {
    createLockerCacheTable(db);
  }
  if (oldVersion < 7) {
    await db.execute("ALTER TABLE $tableApps ADD COLUMN url TEXT;");
  }
  if (oldVersion < 8) {
    await db.execute("ALTER TABLE $tableApps ADD COLUMN processInfoFlags TEXT;");
  }
  if (oldVersion < 9) {
    await db.execute("ALTER TABLE $tableApps ADD COLUMN sdkVersions TEXT;");
  }
}

final AutoDisposeFutureProvider<Database> databaseProvider =
    FutureProvider.autoDispose<Database>((key) async {
  final dbFolder = await (getDatabasesPath() as FutureOr<String>);
  final dbPath = join(dbFolder, "cobble.db");

  final db = await openDatabase(dbPath,
      version: 9,
      onCreate: (db, name) {
        _createDb(db);
      },
      onUpgrade: (db, oldVersion, newVersion) =>
          _upgradeDb(db, oldVersion, newVersion));

  // Note: DB is never closed because closing will cause errors in background
  // code. See https://github.com/tekartik/sqflite/issues/558.
  return db;
});
