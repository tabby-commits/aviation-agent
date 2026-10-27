import { useChatSessionsContext } from "../contexts/chatSessionsContext.ts";

export function useChatSessions() {
  return useChatSessionsContext();
}
