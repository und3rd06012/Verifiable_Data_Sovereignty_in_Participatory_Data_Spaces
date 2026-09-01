import 'models.dart';
import 'outbox.dart';

class ParticipantClient {
  final String participantRef;
  final Outbox outbox;
  final List<ConsentGrant> _grants = [];

  ParticipantClient(this.participantRef, this.outbox);

  void grantConsent(ConsentGrant grant) {
    _grants.add(grant);
  }

  List<ConsentGrant> grants() => List.unmodifiable(_grants);

  Future<void> contribute(String id, Map<String, dynamic> record) async {
    await outbox.enqueue(id, record);
  }

  Future<int> revoke(String field, String purpose, String recipientClass, String atIso) async {
    for (final grant in _grants) {
      if (grant.field == field && grant.purpose == purpose && grant.recipientClass == recipientClass) {
        grant.revokedAt = atIso;
      }
    }
    return outbox.sweepUnsentMatching((body) {
      final fields = (body['fields'] as Map<String, dynamic>?) ?? {};
      return fields.containsKey(field) &&
          body['declaredPurpose'] == purpose &&
          body['declaredRecipientClass'] == recipientClass;
    });
  }
}
