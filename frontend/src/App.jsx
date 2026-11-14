import { Navigate, Route, Routes } from 'react-router-dom';
import { AdminShell, UserShell } from './components/Shell';
import ProtectedRoute from './components/ProtectedRoute';
import AuthPage from './pages/AuthPage';
import ChatPage from './pages/ChatPage';
import KnowledgePage from './pages/KnowledgePage';
import DashboardPage from './pages/DashboardPage';
import AgentRunsPage from './pages/AgentRunsPage';

export default function App() { return <Routes><Route path="/login" element={<AuthPage />} /><Route path="/register" element={<AuthPage register />} /><Route path="/admin/login" element={<AuthPage admin />} /><Route element={<ProtectedRoute />}><Route element={<UserShell />}><Route path="/chat" element={<ChatPage />} /><Route path="/knowledge" element={<KnowledgePage />} /></Route></Route><Route element={<ProtectedRoute admin />}><Route element={<AdminShell />}><Route path="/admin/dashboard" element={<DashboardPage />} /><Route path="/admin/knowledge" element={<KnowledgePage admin />} /><Route path="/admin/agent-runs" element={<AgentRunsPage />} /></Route></Route><Route path="*" element={<Navigate to="/chat" replace />} /></Routes>; }
