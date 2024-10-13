package com.myapp.gattserver;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ShellCommand {

        public static String runShellCommand(String... commands) {
            StringBuilder commandBuilder = new StringBuilder();

            // 将所有参数组合成一个命令字符串
            for (String command : commands) {
                commandBuilder.append(command).append(" ");
            }

            String commandString = commandBuilder.toString().trim(); // 去掉末尾的空格
            StringBuilder output = new StringBuilder();
            Process process = null;

            try {
                // 执行 shell 命令
                process = Runtime.getRuntime().exec(commandString);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;

                // 读取命令输出
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                // 等待命令执行完成
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }

            return output.toString();
        }
    }
