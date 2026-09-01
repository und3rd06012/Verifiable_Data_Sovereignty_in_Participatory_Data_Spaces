import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'session.dart';

class AuditScreen extends StatefulWidget {
  final Session session;

  const AuditScreen({super.key, required this.session});

  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  List<dynamic>? _entries;
  String _error = '';

  Future<void> _load() async {
    setState(() => _error = '');
    final uri = Uri.parse('${widget.session.auditBaseUrl}/${Uri.encodeComponent(widget.session.participantRef)}');
    final response = await http.get(uri, headers: {'authorization': widget.session.authHeader});
    if (response.statusCode == 403) {
      setState(() => _error = 'Not authorized to view this audit trail.');
      return;
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    setState(() => _entries = body['entries'] as List<dynamic>);
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        ElevatedButton(onPressed: _load, child: const Text('Load')),
        if (_error.isNotEmpty) Text(_error),
        if (_entries != null)
          Expanded(
            child: ListView(
              children: _entries!.map((e) {
                final entry = e as Map<String, dynamic>;
                return ListTile(title: Text('${entry['timestamp']}: ${entry['plainLanguage']}'));
              }).toList(),
            ),
          ),
      ],
    );
  }
}
