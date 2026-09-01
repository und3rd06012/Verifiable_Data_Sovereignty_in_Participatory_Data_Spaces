import 'dart:convert';

class Session {
  final String participantRef;
  final String password;
  final String couchDbBaseUrl;
  final String auditBaseUrl;

  Session({
    required this.participantRef,
    required this.password,
    this.couchDbBaseUrl = 'http://localhost:5984',
    this.auditBaseUrl = 'http://localhost:19197/audit',
  });

  String get authHeader {
    final token = '$participantRef:$password';
    return 'Basic ${base64Encode(utf8.encode(token))}';
  }

  String get remoteDatabaseUrl => '$couchDbBaseUrl/participant-$participantRef';
}
