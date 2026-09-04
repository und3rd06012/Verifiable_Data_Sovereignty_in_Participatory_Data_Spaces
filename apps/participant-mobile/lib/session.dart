import 'config.dart';

class Session {
  final String participantRef;
  final String token;

  Session({
    required this.participantRef,
    required this.token,
  });

  String get authHeader => 'Bearer $token';

  String get remoteDatabaseUrl => '$gatewayBaseUrl/couch/participant-$participantRef';

  String get auditBaseUrl => '$gatewayBaseUrl/audit';
}
