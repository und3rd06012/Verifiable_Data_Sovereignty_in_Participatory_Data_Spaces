import 'package:flutter/material.dart';
import 'participant_client.dart';

class DashboardScreen extends StatefulWidget {
  final ParticipantClient client;

  const DashboardScreen({super.key, required this.client});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  @override
  Widget build(BuildContext context) {
    final active = widget.client.grants().where((g) => g.revokedAt == null).toList();
    return ListView(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Text('${active.length} active grant${active.length == 1 ? '' : 's'} of ${widget.client.grants().length} total'),
        ),
        ...active.map((g) => ListTile(
              title: Text(g.field),
              subtitle: Text('shared for ${g.purpose} with ${g.recipientClass}, since ${g.grantedAt}'),
            )),
      ],
    );
  }
}
