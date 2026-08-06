import sqlite3

db_path = '../clawbot.db'
user_id = 'o9cq809zq6o07FPlVt_11gLFUxYU@im.wechat'

conn = sqlite3.connect(db_path)
cursor = conn.cursor()

# Delete messages for this user's conversations
cursor.execute("""
    DELETE FROM messages WHERE conversation_id IN (
        SELECT id FROM conversations WHERE user_id = ?
    )
""", (user_id,))
deleted_messages = cursor.rowcount

# Delete conversations for this user
cursor.execute("DELETE FROM conversations WHERE user_id = ?", (user_id,))
deleted_conversations = cursor.rowcount

conn.commit()
conn.close()

print(f'已删除 {deleted_conversations} 条对话记录, {deleted_messages} 条消息记录')
