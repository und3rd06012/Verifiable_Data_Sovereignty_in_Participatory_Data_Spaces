import 'dart:convert';
import 'package:http/http.dart' as http;
import 'outbox.dart';

class SyncResult {
  final int pushed;
  SyncResult(this.pushed);
}

/// Pushes each unsent outbox record directly to CouchDB via a per-document PUT --
/// no PouchDB replication (the already-recorded Flutter decision, parent spec
/// section 1.2). A record is marked sent only after its own individual PUT
/// succeeds, one at a time, not as a whole-batch operation on a single
/// "replication complete" event the way device-sim's original TypeScript sync
/// does -- this avoids that implementation's own parked gap (a single failed
/// document among several would have marked the whole batch sent regardless).
Future<SyncResult> syncOutbox(Outbox outbox, String remoteUrl, String authHeader) async {
  final pending = await outbox.unsent();
  final pushedIds = <String>[];
  for (final entry in pending) {
    final response = await http.put(
      Uri.parse('$remoteUrl/${entry.key}'),
      headers: {'content-type': 'application/json', 'authorization': authHeader},
      body: jsonEncode(entry.value),
    );
    if (response.statusCode == 201 || response.statusCode == 200) {
      pushedIds.add(entry.key);
    }
  }
  await outbox.markSent(pushedIds);
  return SyncResult(pushedIds.length);
}
