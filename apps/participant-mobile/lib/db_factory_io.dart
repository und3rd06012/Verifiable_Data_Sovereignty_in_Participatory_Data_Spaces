import 'package:sqflite/sqflite.dart';

Future<Database> openAppDatabase(String name) => openDatabase(name);
