import 'package:flutter/material.dart';
import 'audit_screen.dart';
import 'consent_screen.dart';
import 'dashboard_screen.dart';
import 'db_factory.dart';
import 'login_screen.dart';
import 'outbox.dart';
import 'participant_client.dart';
import 'session.dart';

void main() {
  runApp(const BenefitsApp());
}

class BenefitsApp extends StatefulWidget {
  const BenefitsApp({super.key});

  @override
  State<BenefitsApp> createState() => _BenefitsAppState();
}

class _BenefitsAppState extends State<BenefitsApp> {
  Session? _session;
  ParticipantClient? _client;
  int _tab = 0;

  Future<void> _initClient(Session session) async {
    final db = await openAppDatabase('participant-${session.participantRef}.db');
    final outbox = await Outbox.open(db);
    setState(() {
      _client = ParticipantClient(session.participantRef, outbox);
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'BENEFITS participant',
      home: _session == null
          ? LoginScreen(onLogin: (session) {
              setState(() => _session = session);
              _initClient(session);
            })
          : _client == null
              ? const Center(child: CircularProgressIndicator())
              : Scaffold(
                  appBar: AppBar(
                    title: const Text('BENEFITS participant'),
                    actions: [
                      Padding(
                        padding: const EdgeInsets.only(right: 12),
                        child: FilledButton(
                          style: FilledButton.styleFrom(
                            backgroundColor: const Color(0xFF2F6FED),
                            foregroundColor: Colors.white,
                          ),
                          onPressed: () => setState(() {
                            _session = null;
                            _client = null;
                          }),
                          child: const Text('Log out'),
                        ),
                      ),
                    ],
                  ),
                  body: IndexedStack(
                    index: _tab,
                    children: [
                      ConsentScreen(client: _client!, session: _session!),
                      DashboardScreen(client: _client!),
                      AuditScreen(session: _session!),
                    ],
                  ),
                  bottomNavigationBar: BottomNavigationBar(
                    currentIndex: _tab,
                    onTap: (i) => setState(() => _tab = i),
                    items: const [
                      BottomNavigationBarItem(icon: Icon(Icons.check_circle), label: 'Consent'),
                      BottomNavigationBarItem(icon: Icon(Icons.dashboard), label: 'Dashboard'),
                      BottomNavigationBarItem(icon: Icon(Icons.history), label: 'Audit'),
                    ],
                  ),
                ),
    );
  }
}
