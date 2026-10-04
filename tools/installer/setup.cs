// DeepSeek 余额小窗 —— setup bootstrapper.
//
// A package built with IExpress turns out to sit on its extraction step for a 46 MB payload on
// this machine, so the setup .exe is built here instead with the C# compiler that ships with
// Windows: the installer payload is embedded as a resource, this writes it out to a temporary
// folder and hands over to install.ps1, which does the real work (dialog, copying, shortcuts,
// uninstall entry).
//
// No third-party tooling, and the same payload the PowerShell script expects:
//     payload.zip     app jar + bundled Java runtime + uninstall.ps1
//     install.ps1     the installer
//     install.cmd     not used here, kept for the IExpress-style layout
//     app.ico         shortcut icon

using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

internal static class Setup
{
    private static int Main(string[] args)
    {
        string temp = Path.Combine(Path.GetTempPath(), "dstokencheck-setup-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(temp);

            // Everything the installer needs is inside this exe.
            foreach (string name in new[] { "payload.zip", "install.ps1", "app.ico" })
            {
                if (!WriteResource(name, Path.Combine(temp, name)))
                {
                    Fail("安装包损坏：缺少 " + name);
                    return 2;
                }
            }

            string script = Path.Combine(temp, "install.ps1");
            var parts = new System.Collections.Generic.List<string>
            {
                "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script,
                "-PayloadDir", temp
            };
            foreach (string extra in args)
            {
                parts.Add(extra);
            }
            string command = string.Join(" ", parts.ConvertAll(Quote).ToArray());

            ProcessStartInfo start = new ProcessStartInfo("powershell.exe", command);
            start.UseShellExecute = false;
            // The launcher is a windowless executable, so the child's output would otherwise be
            // lost: keeping it makes a failed install explainable afterwards.
            string childLog = Path.Combine(Path.GetTempPath(), "dstokencheck-setup-child.log");
            start.EnvironmentVariables["DSTOKENCHECK_SETUP_CHILD_LOG"] = childLog;
            Process child = Process.Start(start);
            child.WaitForExit();
            return child.ExitCode;
        }
        catch (Exception e)
        {
            Fail("安装程序启动失败：" + e.Message);
            return 1;
        }
        finally
        {
            try { if (Directory.Exists(temp)) Directory.Delete(temp, true); }
            catch { /* the installer's own copies are what matter */ }
        }
    }

    private static bool WriteResource(string name, string path)
    {
        Assembly self = Assembly.GetExecutingAssembly();
        using (Stream input = self.GetManifestResourceStream(name))
        {
            if (input == null) return false;
            using (FileStream output = new FileStream(path, FileMode.Create, FileAccess.Write))
            {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
                {
                    output.Write(buffer, 0, read);
                }
            }
            return true;
        }
    }

    private static string Quote(string value)
    {
        return "\"" + value.Replace("\"", "\\\"") + "\"";
    }

    private static void Fail(string message)
    {
        try
        {
            // The message goes through a file: it contains a path and Chinese quotes, and inlining
            // it into a -Command string makes PowerShell parse the quoting instead of showing it.
            string file = Path.Combine(Path.GetTempPath(), "dstokencheck-setup-error.txt");
            File.WriteAllText(file, message, System.Text.Encoding.UTF8);
            string command = string.Join(" ", Quote("-NoProfile"), Quote("-Command"),
                Quote("Add-Type -AssemblyName System.Windows.Forms; " +
                      "[System.Windows.Forms.MessageBox]::Show((Get-Content -Raw -LiteralPath " +
                      "'" + file + "'), 'DeepSeek 余额小窗 安装程序') | Out-Null"));
            ProcessStartInfo start = new ProcessStartInfo("powershell.exe", command);
            start.UseShellExecute = false;
            Process.Start(start).WaitForExit();
        }
        catch
        {
            Console.Error.WriteLine(message);
        }
    }
}
