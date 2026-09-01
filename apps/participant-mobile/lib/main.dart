import 'package:flutter/material.dart';
import 'audit_screen.dart';
import 'consent_screen.dart';
import 'dashboard_screen.dart';
import 'login_screen.dart';
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
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'BENEFITS participant',
      home: _session == null
          ? LoginScreen(onLogin: (session) => setState(() => _session = session))
          : Scaffold(
              appBar: AppBar(title: const Text('BENEFITS participant')),
              body: [
                ConsentScreen(session: _session!),
                DashboardScreen(session: _session!),
                AuditScreen(session: _session!),
              ][_tab],
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
