export type WebUserRole = 'ADMIN' | 'OPERATOR';

export type WebUser = {
  id: number;
  provider: string;
  username: string;
  displayName: string;
  role: WebUserRole;
  enabled: boolean;
  lastLoginAt?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type WebUserForm = {
  username: string;
  displayName: string;
  role: WebUserRole;
  password?: string;
  enabled: boolean;
};
