import 'package:flutter/material.dart';
import 'package:sqflite/sqflite.dart';
import 'models.dart';
import 'outbox.dart';
import 'participant_client.dart';
import 'session.dart';
import 'sync.dart';

class ConsentScreen extends StatefulWidget {
  final Session session;

  const ConsentScreen({super.key, required this.session});

  @override
  State<ConsentScreen> createState() => _ConsentScreenState();
}

class _ConsentScreenState extends State<ConsentScreen> {
  ParticipantClient? _client;
  String _status = '';

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final db = await openDatabase('participant-${widget.session.participantRef}.db');
    final outbox = await Outbox.open(db);
    setState(() {
      _client = ParticipantClient(widget.session.participantRef, outbox);
    });
  }

  Future<void> _grant() async {
    _client!.grantConsent(ConsentGrant(
      field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
      grantedAt: DateTime.now().toUtc().toIso8601String(),
    ));
    setState(() {});
  }

  Future<void> _revoke(ConsentGrant grant) async {
    final swept = await _client!.revoke(grant.field, grant.purpose, grant.recipientClass, DateTime.now().toUtc().toIso8601String());
    setState(() {
      _status = 'revoked (swept $swept queued record${swept == 1 ? '' : 's'})';
    });
  }

  Future<void> _sync() async {
    setState(() => _status = 'syncing...');
    final result = await syncOutbox(_client!.outbox, widget.session.remoteDatabaseUrl, widget.session.authHeader);
    setState(() => _status = 'pushed ${result.pushed} record${result.pushed == 1 ? '' : 's'}');
  }

  @override
  Widget build(BuildContext context) {
    if (_client == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return Column(
      children: [
        ElevatedButton(onPressed: _grant, child: const Text('Grant: satisfaction data for service improvement')),
        Expanded(
          child: ListView(
            children: _client!.grants().map((g) {
              return ListTile(
                title: Text('${g.field} / ${g.purpose} / ${g.recipientClass}'),
                subtitle: g.revokedAt != null ? const Text('revoked') : null,
                trailing: g.revokedAt == null
                    ? TextButton(onPressed: () => _revoke(g), child: const Text('Revoke'))
                    : null,
              );
            }).toList(),
          ),
        ),
        ElevatedButton(onPressed: _sync, child: const Text('Sync now')),
        Text(_status),
      ],
    );
  }
}
