import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:benefits_participant_mobile/outbox.dart';

void main() {
  sqfliteFfiInit();

  test('enqueue then unsent returns the record', () async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    addTearDown(db.close);
    final outbox = await Outbox.open(db);
    await outbox.enqueue('c-1', {'type': 'contribution', 'fields': {'service.satisfaction': 4}});
    final unsent = await outbox.unsent();
    expect(unsent.length, 1);
    expect(unsent.first.key, 'c-1');
  });

  test('markSent excludes a record from unsent', () async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    addTearDown(db.close);
    final outbox = await Outbox.open(db);
    await outbox.enqueue('c-2', {'type': 'contribution'});
    await outbox.markSent(['c-2']);
    final unsent = await outbox.unsent();
    expect(unsent, isEmpty);
  });

  test('sweepUnsentMatching deletes only matching unsent records', () async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    addTearDown(db.close);
    final outbox = await Outbox.open(db);
    await outbox.enqueue('c-3', {
      'fields': {'service.satisfaction': 4},
      'declaredPurpose': 'service-improvement',
      'declaredRecipientClass': 'statistics'
    });
    await outbox.enqueue('c-4', {
      'fields': {'other.field': 1},
      'declaredPurpose': 'marketing',
      'declaredRecipientClass': 'provider'
    });
    final swept = await outbox.sweepUnsentMatching((body) =>
        (body['fields'] as Map).containsKey('service.satisfaction') &&
        body['declaredPurpose'] == 'service-improvement');
    expect(swept, 1);
    final remaining = await outbox.unsent();
    expect(remaining.map((e) => e.key), contains('c-4'));
    expect(remaining.map((e) => e.key), isNot(contains('c-3')));
  });

  test('does not sweep an already-sent record on a later revoke', () async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    addTearDown(db.close);
    final outbox = await Outbox.open(db);
    await outbox.enqueue('c-5', {
      'fields': {'service.satisfaction': 4},
      'declaredPurpose': 'service-improvement',
      'declaredRecipientClass': 'statistics'
    });
    await outbox.markSent(['c-5']);
    final swept = await outbox.sweepUnsentMatching((body) =>
        (body['fields'] as Map).containsKey('service.satisfaction') &&
        body['declaredPurpose'] == 'service-improvement');
    expect(swept, 0);
  });
}
