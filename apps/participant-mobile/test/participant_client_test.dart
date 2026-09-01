import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:benefits_participant_mobile/models.dart';
import 'package:benefits_participant_mobile/outbox.dart';
import 'package:benefits_participant_mobile/participant_client.dart';

void main() {
  sqfliteFfiInit();

  Future<ParticipantClient> newClient() async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    final outbox = await Outbox.open(db);
    return ParticipantClient('p-1', outbox);
  }

  test('grantConsent then grants returns the grant', () async {
    final client = await newClient();
    addTearDown(client.outbox.db.close);
    client.grantConsent(ConsentGrant(
      field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
      grantedAt: '2026-09-01T00:00:00Z',
    ));
    expect(client.grants().length, 1);
  });

  test('revoke stamps revokedAt and sweeps only the matching unsent record', () async {
    final client = await newClient();
    addTearDown(client.outbox.db.close);
    client.grantConsent(ConsentGrant(
      field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
      grantedAt: '2026-09-01T00:00:00Z',
    ));
    await client.contribute('c-1', {
      'type': 'contribution',
      'fields': {'service.satisfaction': 4},
      'declaredPurpose': 'service-improvement',
      'declaredRecipientClass': 'statistics',
    });

    final swept = await client.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-09-01T01:00:00Z');

    expect(swept, 1);
    expect(client.grants().first.revokedAt, '2026-09-01T01:00:00Z');
  });
}
