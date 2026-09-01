class ConsentGrant {
  final String field;
  final String purpose;
  final String recipientClass;
  final String grantedAt;
  String? expiresAt;
  String? revokedAt;

  ConsentGrant({
    required this.field,
    required this.purpose,
    required this.recipientClass,
    required this.grantedAt,
    this.expiresAt,
    this.revokedAt,
  });

  Map<String, dynamic> toJson() => {
        'field': field,
        'purpose': purpose,
        'recipientClass': recipientClass,
        'grantedAt': grantedAt,
        'expiresAt': expiresAt,
        'revokedAt': revokedAt,
      };
}
