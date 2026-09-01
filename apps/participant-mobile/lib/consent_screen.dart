import 'package:flutter/material.dart';
import 'models.dart';
import 'participant_client.dart';
import 'session.dart';
import 'sync.dart';

class ConsentScreen extends StatefulWidget {
  final ParticipantClient client;
  final Session session;

  const ConsentScreen({super.key, required this.client, required this.session});

  @override
  State<ConsentScreen> createState() => _ConsentScreenState();
}

class _ConsentScreenState extends State<ConsentScreen> {
  String _status = '';

  Future<void> _grant() async {
    widget.client.grantConsent(ConsentGrant(
      field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
      grantedAt: DateTime.now().toUtc().toIso8601String(),
    ));
    setState(() {});
  }

  Future<void> _contribute() async {
    await widget.client.contribute(
      'contrib-${DateTime.now().microsecondsSinceEpoch}',
      {
        'type': 'contribution',
        'declaredPurpose': 'service-improvement',
        'declaredRecipientClass': 'statistics',
        'fields': {'service.satisfaction': 4},
      },
    );
    setState(() => _status = 'contribution queued');
  }

  Future<void> _revoke(ConsentGrant grant) async {
    final swept = await widget.client.revoke(grant.field, grant.purpose, grant.recipientClass, DateTime.now().toUtc().toIso8601String());
    setState(() {
      _status = 'revoked (swept $swept queued record${swept == 1 ? '' : 's'})';
    });
  }

  Future<void> _sync() async {
    setState(() => _status = 'syncing...');
    final result = await syncOutbox(widget.client.outbox, widget.session.remoteDatabaseUrl, widget.session.authHeader);
    setState(() => _status = 'pushed ${result.pushed} record${result.pushed == 1 ? '' : 's'}');
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        ElevatedButton(onPressed: _grant, child: const Text('Grant: satisfaction data for service improvement')),
        ElevatedButton(onPressed: _contribute, child: const Text('Contribute: satisfaction data')),
        Expanded(
          child: ListView(
            children: widget.client.grants().map((g) {
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
