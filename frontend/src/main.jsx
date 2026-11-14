import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { restoreSession } from './api/client';
import './styles.css';

restoreSession().finally(() => {
  createRoot(document.getElementById('root')).render(
    <React.StrictMode><BrowserRouter><App /></BrowserRouter></React.StrictMode>,
  );
});
