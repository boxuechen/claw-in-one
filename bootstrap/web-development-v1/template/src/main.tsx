/// <reference types="vite/client" />

import React from 'react';
import {createRoot} from 'react-dom/client';
import './style.css';

const APP_NAME = __APP_NAME_JSON__;

function App() {
  const [message, setMessage] = React.useState('Connecting to the API…');

  React.useEffect(() => {
    fetch('/api/hello')
      .then(response => {
        if (!response.ok) throw new Error('request failed');
        return response.json() as Promise<{message: string}>;
      })
      .then(value => setMessage(value.message))
      .catch(() => setMessage('The API is not available.'));
  }, []);

  return (
    <main>
      <section className="card">
        <span className="eyebrow">CLAWINONE WEB</span>
        <h1>{APP_NAME}</h1>
        <p>{message}</p>
        <button type="button" onClick={() => setMessage('Hello from Android Chrome!')}>
          Try it
        </button>
      </section>
    </main>
  );
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
