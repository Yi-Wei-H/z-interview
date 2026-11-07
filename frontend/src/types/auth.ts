export interface UserDTO {
  id: number;
  userid: string;
  username: string | null;
}

export interface LoginResponse {
  token: string;
  user: UserDTO;
}

export interface RegisterRequest {
  password: string;
  username?: string;
}
