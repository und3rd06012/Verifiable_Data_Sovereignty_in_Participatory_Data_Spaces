import 'package:flutter/material.dart';
import 'session.dart';

class LoginScreen extends StatefulWidget {
  final void Function(Session session) onLogin;

  const LoginScreen({super.key, required this.onLogin});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _participantRefController = TextEditingController();
  final _passwordController = TextEditingController();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('BENEFITS participant')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            TextField(controller: _participantRefController, decoration: const InputDecoration(labelText: 'Participant ID')),
            TextField(controller: _passwordController, decoration: const InputDecoration(labelText: 'Password'), obscureText: true),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: () {
                widget.onLogin(Session(
                  participantRef: _participantRefController.text,
                  password: _passwordController.text,
                ));
              },
              child: const Text('Continue'),
            ),
          ],
        ),
      ),
    );
  }
}
