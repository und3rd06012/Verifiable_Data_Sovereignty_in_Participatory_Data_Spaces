import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'config.dart';
import 'session.dart';

class LoginScreen extends StatefulWidget {
  final void Function(Session session) onLogin;

  const LoginScreen({super.key, required this.onLogin});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

enum _Step { password, twoFactor, enroll }

class _LoginScreenState extends State<LoginScreen> {
  final _participantRefController = TextEditingController();
  final _passwordController = TextEditingController();
  final _codeController = TextEditingController();

  _Step _step = _Step.password;
  List<String> _methods = [];
  String _method = 'totp';
  String _otpauthUri = '';
  String _secret = '';
  String _error = '';
  bool _busy = false;

  Future<Map<String, dynamic>> _post(String path, Map<String, dynamic> body) async {
    final response = await http.post(
      Uri.parse('$gatewayBaseUrl$path'),
      headers: {'content-type': 'application/json'},
      body: jsonEncode(body),
    );
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  Future<void> _submitPassword() async {
    setState(() {
      _busy = true;
      _error = '';
    });
    try {
      final body = await _post('/auth/login', {
        'username': _participantRefController.text,
        'password': _passwordController.text,
      });
      if (body.containsKey('error')) {
        setState(() => _error = body['error'] as String);
        return;
      }
      final methods = (body['methods'] as List<dynamic>).cast<String>();
      setState(() {
        _methods = methods;
        _method = methods.isEmpty ? 'totp' : methods.first;
        _step = body['stage'] == 'enroll' ? _Step.enroll : _Step.twoFactor;
      });
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _sendCode() async {
    setState(() {
      _busy = true;
      _error = '';
    });
    try {
      final body = await _post('/auth/request', {
        'username': _participantRefController.text,
        'password': _passwordController.text,
        'method': _method,
      });
      if (body.containsKey('error')) {
        setState(() => _error = body['error'] as String);
      }
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _setupTotp() async {
    setState(() {
      _busy = true;
      _error = '';
    });
    try {
      final body = await _post('/auth/enroll', {
        'username': _participantRefController.text,
        'password': _passwordController.text,
        'method': 'totp',
      });
      if (body.containsKey('error')) {
        setState(() => _error = body['error'] as String);
        return;
      }
      setState(() {
        _otpauthUri = body['otpauthUri'] as String? ?? '';
        _secret = body['secret'] as String? ?? '';
      });
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _verify() async {
    setState(() {
      _busy = true;
      _error = '';
    });
    try {
      final body = await _post('/auth/verify', {
        'username': _participantRefController.text,
        'password': _passwordController.text,
        'method': _method,
        'code': _codeController.text,
      });
      if (body.containsKey('error')) {
        setState(() => _error = body['error'] as String);
        return;
      }
      widget.onLogin(Session(
        participantRef: _participantRefController.text,
        token: body['token'] as String,
      ));
    } finally {
      setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('BENEFITS participant')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: ListView(
          children: [
            if (_step == _Step.password) ...[
              TextField(controller: _participantRefController, decoration: const InputDecoration(labelText: 'Participant ID')),
              TextField(controller: _passwordController, decoration: const InputDecoration(labelText: 'Password'), obscureText: true),
              const SizedBox(height: 16),
              ElevatedButton(onPressed: _busy ? null : _submitPassword, child: const Text('Continue')),
            ] else if (_step == _Step.enroll) ...[
              const Text('This account has no second factor yet. Set one up before continuing.'),
              const SizedBox(height: 12),
              ElevatedButton(onPressed: _busy ? null : _setupTotp, child: const Text('Set up authenticator app (TOTP)')),
              if (_otpauthUri.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text('Secret: $_secret', style: const TextStyle(fontFamily: 'monospace')),
                const Text('otpauth URI (scan or enter manually):'),
                SelectableText(_otpauthUri),
                const SizedBox(height: 12),
                ElevatedButton(
                  onPressed: () => setState(() {
                    _methods = ['totp'];
                    _method = 'totp';
                    _step = _Step.twoFactor;
                  }),
                  child: const Text('I have added it, continue to code'),
                ),
              ],
            ] else ...[
              Text('Second factor for ${_participantRefController.text}'),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _method,
                items: _methods.map((m) => DropdownMenuItem(
                  value: m,
                  child: Text(m == 'totp' ? 'Authenticator app (TOTP)' : m == 'email' ? 'Email code' : 'SMS code'),
                )).toList(),
                onChanged: (value) => setState(() => _method = value ?? _method),
              ),
              if (_method != 'totp') ...[
                const SizedBox(height: 8),
                ElevatedButton(onPressed: _busy ? null : _sendCode, child: const Text('Send code')),
              ],
              TextField(controller: _codeController, decoration: const InputDecoration(labelText: 'Code')),
              const SizedBox(height: 16),
              ElevatedButton(onPressed: _busy ? null : _verify, child: const Text('Verify and continue')),
            ],
            if (_error.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(_error, style: const TextStyle(color: Colors.red)),
            ],
          ],
        ),
      ),
    );
  }
}
