import { useSyncExternalStore } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { authState } from '../api/client';

export default function ProtectedRoute({ admin = false }) {
  const location = useLocation();
  const token = useSyncExternalStore(authState.subscribe, () => authState.token, () => null);
  const authenticated = Boolean(token);
  const expectedRole = admin ? 'ADMIN' : 'USER';
  const allowed = authenticated && authState.role === expectedRole;
  return allowed
    ? <Outlet />
    : <Navigate to={admin ? '/admin/login' : '/login'} replace state={{ from: location.pathname }} />;
}
