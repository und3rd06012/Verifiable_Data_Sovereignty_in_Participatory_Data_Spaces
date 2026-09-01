import 'package:flutter/material.dart';
import 'package:sqflite/sqflite.dart';
import 'outbox.dart';
import 'participant_client.dart';
import 'session.dart';

class DashboardScreen extends StatefulWidget {
  final Session session;

  const DashboardScreen({super.key, required this.session});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  ParticipantClient? _client;

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

  @override
  Widget build(BuildContext context) {
    if (_client == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final active = _client!.grants().where((g) => g.revokedAt == null).toList();
    return ListView(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Text('${active.length} active grant${active.length == 1 ? '' : 's'} of ${_client!.grants().length} total'),
        ),
        ...active.map((g) => ListTile(
              title: Text(g.field),
              subtitle: Text('shared for ${g.purpose} with ${g.recipientClass}, since ${g.grantedAt}'),
            )),
      ],
    );
  }
}
