export type FieldId = string;
export type PurposeId = string;
export type RecipientClass = 'community' | 'provider' | 'statistics';

export interface ConsentGrant {
  field: FieldId;
  purpose: PurposeId;
  recipientClass: RecipientClass;
  grantedAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
}

export interface ConsentAssertion {
  participantRef: string;
  grants: ConsentGrant[];
  assertedAt: string;
}

export interface ContributionRecord {
  _id?: string;
  _rev?: string;
  _deleted?: boolean;
  type: string;
  participantRef: string;
  siteId: string;
  collectedAt: string;
  channel: 'app' | 'sms' | 'paper' | 'voice';
  declaredPurpose: PurposeId;
  declaredRecipientClass: RecipientClass;
  fields: { [field: string]: unknown };
  consent: ConsentAssertion;
}

export interface Decision {
  allowed: boolean;
  reason: string;
}
