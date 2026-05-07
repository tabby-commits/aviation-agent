import React, { useState } from "react";
import { Sender } from "@ant-design/x";

interface AgentChatInputProps {
  onSend: (message: string) => void;
}

const AgentChatInput: React.FC<AgentChatInputProps> = ({ onSend }) => {
  const [message, setMessage] = useState("");

  return (
    <div className="rounded-xl bg-white">
      <Sender
        onSubmit={() => {
          onSend(message.trim());
          setMessage("");
        }}
        placeholder="输入航天科技情报问题或研判任务..."
        value={message}
        onChange={setMessage}
      />
    </div>
  );
};

export default AgentChatInput;
