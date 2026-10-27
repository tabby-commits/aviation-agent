import { BrowserRouter, Route, Routes } from "react-router-dom";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import LoginView from "./components/views/LoginView.tsx";
import RequireAuth from "./components/RequireAuth.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginView />} />
        <Route
          path="/*"
          element={
            <RequireAuth>
              <ChatSessionsProvider>
                <JChatMindLayout />
              </ChatSessionsProvider>
            </RequireAuth>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
