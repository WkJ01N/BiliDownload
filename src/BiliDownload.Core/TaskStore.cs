using Microsoft.Data.Sqlite;
using System.Text.Json;

namespace BiliDownload.Core;

public sealed class TaskStore
{
    private readonly string connectionString;
    private readonly SemaphoreSlim gate = new(1);
    public TaskStore(string directory)
    {
        Directory.CreateDirectory(directory);
        connectionString = new SqliteConnectionStringBuilder { DataSource = Path.Combine(directory, "tasks.db") }.ToString();
        using var db = Open();
        using var cmd = db.CreateCommand();
        cmd.CommandText = "CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, data TEXT NOT NULL)";
        cmd.ExecuteNonQuery();
    }
    private SqliteConnection Open() { var db = new SqliteConnection(connectionString); db.Open(); return db; }
    public IReadOnlyList<DownloadTaskData> Load()
    {
        using var db = Open(); using var cmd = db.CreateCommand(); cmd.CommandText = "SELECT data FROM tasks";
        using var reader = cmd.ExecuteReader(); var list = new List<DownloadTaskData>();
        while (reader.Read()) if (JsonSerializer.Deserialize<DownloadTaskData>(reader.GetString(0)) is { } item) list.Add(item);
        return list;
    }
    public async Task SaveAsync(DownloadTaskData item)
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            using var db = Open(); using var cmd = db.CreateCommand();
            cmd.CommandText = "INSERT INTO tasks(id,data) VALUES($id,$data) ON CONFLICT(id) DO UPDATE SET data=$data";
            cmd.Parameters.AddWithValue("$id", item.Id.ToString());
            cmd.Parameters.AddWithValue("$data", JsonSerializer.Serialize(item));
            await cmd.ExecuteNonQueryAsync().ConfigureAwait(false);
        }
        finally { gate.Release(); }
    }
    public async Task DeleteAsync(Guid id) {
        await gate.WaitAsync();
        try { using var db = Open(); using var cmd = db.CreateCommand(); cmd.CommandText = "DELETE FROM tasks WHERE id=$id"; cmd.Parameters.AddWithValue("$id", id.ToString()); cmd.ExecuteNonQuery(); }
        finally { gate.Release(); }
    }
}
