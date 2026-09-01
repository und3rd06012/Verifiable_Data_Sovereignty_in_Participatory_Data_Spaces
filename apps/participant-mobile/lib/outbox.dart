import 'dart:convert';
import 'package:sqflite/sqflite.dart';

class Outbox {
  final Database db;

  Outbox._(this.db);

  static Future<Outbox> open(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS outbox (
        id TEXT PRIMARY KEY,
        body TEXT NOT NULL,
        sent INTEGER NOT NULL DEFAULT 0
      )
    ''');
    return Outbox._(db);
  }

  Future<void> enqueue(String id, Map<String, dynamic> body) async {
    await db.insert(
      'outbox',
      {'id': id, 'body': jsonEncode(body), 'sent': 0},
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<List<MapEntry<String, Map<String, dynamic>>>> unsent() async {
    final rows = await db.query('outbox', where: 'sent = 0');
    return rows
        .map((row) => MapEntry(row['id'] as String, jsonDecode(row['body'] as String) as Map<String, dynamic>))
        .toList();
  }

  Future<void> markSent(List<String> ids) async {
    if (ids.isEmpty) {
      return;
    }
    final placeholders = List.filled(ids.length, '?').join(',');
    await db.update('outbox', {'sent': 1}, where: 'id IN ($placeholders)', whereArgs: ids);
  }

  Future<int> sweepUnsentMatching(bool Function(Map<String, dynamic> body) predicate) async {
    final rows = await db.query('outbox', where: 'sent = 0');
    var swept = 0;
    for (final row in rows) {
      final body = jsonDecode(row['body'] as String) as Map<String, dynamic>;
      if (predicate(body)) {
        await db.delete('outbox', where: 'id = ?', whereArgs: [row['id']]);
        swept++;
      }
    }
    return swept;
  }
}
