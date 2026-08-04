#!/usr/bin/env python3
"""
GitHub CI 自动修复脚本 (GitHub CI Auto Fix Loop)
==================================================
检查 GitHub Actions 编译状态 → 如果失败，用 AI 修复代码 → 推送 → 等待 CI → 重复

符合规则：不在本地编译，在 GitHub 上编译。
不输出任何日志文件，不在本地编译。

用法:
  python auto_fix.py [选项]

选项:
  --repo OWNER/REPO        GitHub 仓库 (默认: ojbkxc/Agora)
  --branch BRANCH          分支 (默认: master)
  --project-dir PATH       项目本地目录 (默认: D:\\GitHub\\Agora)
  --max-iterations N       最大迭代次数 (默认: 10)
  --cc-haha-src PATH       cc-haha 源码目录 (默认: D:\\GitHub\\cc-haha-main)
  --github-token TOKEN     GitHub token (可选，用于推送和获取日志)
  --prompt "PROMPT"        自定义修复提示词
  --check-only             仅检查 CI 状态，不修复
  --dry-run                仅模拟运行
  --quiet                  完全静默，不输出任何内容到控制台
  --wait-interval SECONDS  等待 CI 轮询间隔 (默认: 60)
  --ci-timeout SECONDS     等待 CI 完成超时 (默认: 1200)

工作流程:
  1. 检查 GitHub Actions 最新运行状态 (公开 API，无需 token)
  2. 如果全部成功 → 退出
  3. 如果有失败 → 获取失败任务信息
  4. 用 AI (bun) 修复代码
  5. 通过文件修改时间戳检测修改的文件（不依赖 git）
  6. 如果有 token → 自动推送；否则输出 changed_files.json 供 TRAE 推送
  7. 等待新的 CI 运行完成
  8. 回到步骤 1
"""

import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


# ============================================================
# bun 路径自动检测
# ============================================================
def _find_bun() -> str:
    """查找 bun 可执行文件路径"""
    try:
        r = subprocess.run("bun --version", shell=True, capture_output=True, text=True, timeout=10)
        if r.returncode == 0 and r.stdout.strip():
            return "bun"
    except Exception:
        pass

    candidates = []
    appdata = os.environ.get("APPDATA", "")
    if appdata:
        candidates.append(
            Path(appdata) / "TRAE SOLO CN" / "ModularData" / "ai-agent" / "vm" / "tools" / "node" / "node_modules" / "bun" / "bin" / "bun.exe"
        )
    localappdata = os.environ.get("LOCALAPPDATA", "")
    if localappdata:
        candidates.append(Path(localappdata) / "npm" / "bun.exe")
    userprofile = os.environ.get("USERPROFILE", "")
    if userprofile:
        candidates.append(Path(userprofile) / ".bun" / "bin" / "bun.exe")

    for p in candidates:
        if p.exists():
            return f'"{p}"'

    return ""


# ============================================================
# 默认配置
# ============================================================
DEFAULT_CONFIG = {
    "repo": "ojbkxc/Agora",
    "branch": "master",
    "project_dir": r"D:\GitHub\Agora",
    "cc_haha_src": r"D:\GitHub\cc-haha-main",
    "max_iterations": 10,
    "github_token": None,
    "prompt": None,
    "dry_run": False,
    "quiet": False,
    "check_only": False,
    "wait_interval": 60,
    "ci_timeout": 1200,
    "timeout_fix": 600,
}


# ============================================================
# 日志输出（只写控制台，不写文件）
# ============================================================
class Logger:
    def __init__(self, quiet: bool = False):
        self.quiet = quiet

    def log(self, msg: str, level: str = "INFO", force: bool = False):
        if self.quiet and not force:
            return
        timestamp = datetime.now().strftime("%H:%M:%S")
        print(f"[{timestamp}] [{level}] {msg}")

    def section(self, title: str):
        if self.quiet:
            return
        print("=" * 60)
        print(f"  {title}")
        print("=" * 60)

    def close(self):
        pass


# ============================================================
# 命令执行器
# ============================================================
class CommandRunner:
    def __init__(self, logger: Logger, dry_run: bool = False):
        self.logger = logger
        self.dry_run = dry_run

    def run(self, cmd, cwd=None, timeout=120, env=None,
            capture_stdout=True, capture_stderr=True) -> Tuple[int, str, str]:
        cwd_str = cwd or os.getcwd()
        cmd_str = cmd if isinstance(cmd, str) else " ".join(cmd)

        self.logger.log(f"执行: {cmd_str}")
        self.logger.log(f"目录: {cwd_str}")

        if self.dry_run:
            self.logger.log("[DRY-RUN] 跳过实际执行", "WARN")
            return 0, "[DRY-RUN] no output", ""

        try:
            merged_env = os.environ.copy()
            merged_env.setdefault("FORCE_COLOR", "0")
            if env:
                merged_env.update(env)

            process = subprocess.Popen(
                cmd_str,
                shell=True,
                cwd=cwd_str,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                env=merged_env,
            )

            stdout_chunks = []
            stderr_chunks = []

            try:
                def read_stream(stream, chunks):
                    for line in stream:
                        chunks.append(line)
                    stream.close()

                stdout_thread = threading.Thread(target=read_stream, args=(process.stdout, stdout_chunks))
                stderr_thread = threading.Thread(target=read_stream, args=(process.stderr, stderr_chunks))
                stdout_thread.daemon = True
                stderr_thread.daemon = True
                stdout_thread.start()
                stderr_thread.start()

                process.wait(timeout=timeout)
                stdout_thread.join(timeout=5)
                stderr_thread.join(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                self.logger.log(f"命令超时 ({timeout}s)", "ERROR")
                return -1, "".join(stdout_chunks).strip(), f"TIMEOUT after {timeout}s"

            stdout = "".join(stdout_chunks).strip() if capture_stdout else ""
            stderr = "".join(stderr_chunks).strip() if capture_stderr else ""

            if stdout:
                self.logger.log(f"stdout ({len(stdout)} chars): {stdout[:500]}")
            if stderr:
                self.logger.log(f"stderr ({len(stderr)} chars): {stderr[:500]}")

            return process.returncode, stdout, stderr

        except Exception as e:
            self.logger.log(f"命令执行异常: {e}", "ERROR")
            return -1, "", str(e)


# ============================================================
# GitHub Actions CI 检查器
# ============================================================
class GitHubActionsChecker:
    """通过 GitHub API 检查 Actions CI 状态（公开仓库无需 token）"""

    API_BASE = "https://api.github.com"

    def __init__(self, config: Dict, logger: Logger):
        self.repo = config["repo"]
        self.branch = config["branch"]
        self.token = config.get("github_token")
        self.logger = logger

    def _api_get(self, endpoint: str, retries: int = 3) -> Optional[Any]:
        """调用 GitHub API（带重试）"""
        url = f"{self.API_BASE}/repos/{self.repo}/{endpoint}"
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "auto-fix-script",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        for attempt in range(1, retries + 1):
            try:
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=30) as resp:
                    return json.loads(resp.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                if attempt < retries:
                    wait = min(attempt * 5, 15)
                    self.logger.log(f"API 请求重试 ({attempt}/{retries}): {e.code} - 等待 {wait}s", "WARN")
                    time.sleep(wait)
                else:
                    self.logger.log(f"API 请求失败: {e.code} {e.reason} - {url}", "ERROR")
            except Exception as e:
                if attempt < retries:
                    wait = min(attempt * 5, 15)
                    self.logger.log(f"API 请求异常重试 ({attempt}/{retries}): {e}", "WARN")
                    time.sleep(wait)
                else:
                    self.logger.log(f"API 请求异常: {e}", "ERROR")
        return None

    def get_latest_runs(self, per_page: int = 5) -> List[Dict]:
        """获取最近的 Actions 运行"""
        data = self._api_get(f"actions/runs?per_page={per_page}&branch={self.branch}")
        if data and "workflow_runs" in data:
            return data["workflow_runs"]
        return []

    def get_latest_run_for_commit(self, commit_sha: str) -> Optional[Dict]:
        """获取指定 commit 的最新运行"""
        data = self._api_get(f"actions/runs?head_sha={commit_sha}&per_page=10")
        if data and "workflow_runs" in data and data["workflow_runs"]:
            runs = data["workflow_runs"]
            completed = [r for r in runs if r["status"] == "completed"]
            if completed:
                return completed[0]
            return runs[0]
        return None

    def get_run_jobs(self, run_id: int) -> List[Dict]:
        """获取运行的 jobs 详情"""
        data = self._api_get(f"actions/runs/{run_id}/jobs")
        if data and "jobs" in data:
            return data["jobs"]
        return []

    def check_ci_status(self) -> Dict:
        """
        检查最新 CI 状态
        返回: {
            "all_passed": bool,
            "runs": [...],
            "failed_runs": [...],
            "latest_commit": str,
            "error_info": str
        }
        """
        self.logger.section("检查 GitHub Actions CI 状态")

        runs = self.get_latest_runs(per_page=10)
        if not runs:
            self.logger.log("未找到任何 Actions 运行", "WARN")
            return {"all_passed": True, "runs": [], "failed_runs": [], "latest_commit": "", "error_info": "No runs found"}

        latest_commit = runs[0].get("head_sha", "")
        latest_runs = [r for r in runs if r.get("head_sha") == latest_commit]

        self.logger.log(f"最新 commit: {latest_commit[:12]}")
        self.logger.log(f"该 commit 的 CI 运行数: {len(latest_runs)}")

        all_passed = True
        failed_runs = []
        in_progress = []

        for run in latest_runs:
            status = run.get("status", "unknown")
            conclusion = run.get("conclusion", "unknown")
            name = run.get("name", "unknown")
            run_id = run.get("id")

            if status != "completed":
                in_progress.append(run)
                self.logger.log(f"  [{name}] 状态: {status} (进行中...)", "WARN")
                all_passed = False
            elif conclusion == "success":
                self.logger.log(f"  [{name}] 状态: {conclusion} ✓", "SUCCESS")
            else:
                self.logger.log(f"  [{name}] 状态: {conclusion} ✗ (run #{run_id})", "ERROR")
                failed_runs.append(run)
                all_passed = False

        if in_progress:
            return {
                "all_passed": False,
                "in_progress": True,
                "runs": latest_runs,
                "failed_runs": [],
                "latest_commit": latest_commit,
                "error_info": "CI runs are still in progress",
            }

        error_info = ""
        if failed_runs:
            error_info = self._collect_error_details(failed_runs)

        return {
            "all_passed": all_passed,
            "in_progress": False,
            "runs": latest_runs,
            "failed_runs": failed_runs,
            "latest_commit": latest_commit,
            "error_info": error_info,
        }

    def _collect_error_details(self, failed_runs: List[Dict]) -> str:
        """从失败的运行中收集错误详情"""
        details = []

        for run in failed_runs:
            run_id = run.get("id")
            name = run.get("name", "unknown")
            title = run.get("display_title", run.get("head_commit", {}).get("message", "unknown"))
            html_url = run.get("html_url", "")

            details.append(f"=" * 50)
            details.append(f"失败运行: {name}")
            details.append(f"Run ID: {run_id}")
            details.append(f"标题: {title}")
            details.append(f"URL: {html_url}")
            details.append(f"=" * 50)

            jobs = self.get_run_jobs(run_id)
            for job in jobs:
                job_name = job.get("name", "unknown")
                job_conclusion = job.get("conclusion", "unknown")

                if job_conclusion != "success":
                    details.append(f"\n失败任务: {job_name} (conclusion: {job_conclusion})")

                    steps = job.get("steps", [])
                    for step in steps:
                        step_name = step.get("name", "unknown")
                        step_conclusion = step.get("conclusion", "unknown")
                        step_number = step.get("number", 0)
                        if step_conclusion in ("failure", "cancelled", "timed_out"):
                            details.append(f"  步骤 #{step_number}: {step_name} → {step_conclusion}")

                    if self.token:
                        log_content = self._get_job_logs(job.get("id"))
                        if log_content:
                            lines = log_content.split("\n")
                            error_lines = [l for l in lines if any(kw in l.lower() for kw in ["error", "fail", "panic", "exception", "fatal", "compile", "cannot", "not found"])]
                            if error_lines:
                                details.append(f"  错误日志 (最后 {min(50, len(error_lines))} 行):")
                                for line in error_lines[-50:]:
                                    details.append(f"    {line}")
                            else:
                                details.append(f"  日志 (最后 30 行):")
                                for line in lines[-30:]:
                                    details.append(f"    {line}")
                    else:
                        details.append(f"  (无 token，无法获取完整日志)")
                        failed_steps = [s for s in steps if s.get("conclusion") in ("failure", "cancelled", "timed_out")]
                        for step in failed_steps:
                            details.append(f"  失败步骤: {step.get('name', 'unknown')}")

            details.append("")

        return "\n".join(details)

    def _get_job_logs(self, job_id: int) -> Optional[str]:
        """获取 job 日志（需要 token）"""
        if not self.token:
            return None

        url = f"{self.API_BASE}/repos/{self.repo}/actions/jobs/{job_id}/logs"
        headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "User-Agent": "auto-fix-script",
        }

        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception as e:
            self.logger.log(f"获取 job 日志失败: {e}", "WARN")
            return None

    def wait_for_ci_completion(self, commit_sha: str, timeout: int = 1200, interval: int = 60) -> Optional[Dict]:
        """等待指定 commit 的 CI 运行完成"""
        self.logger.log(f"等待 CI 完成 (commit: {commit_sha[:12]})...")

        start_time = time.time()
        while time.time() - start_time < timeout:
            run = self.get_latest_run_for_commit(commit_sha)
            if run:
                status = run.get("status", "unknown")
                conclusion = run.get("conclusion", "unknown")
                name = run.get("name", "unknown")

                if status == "completed":
                    self.logger.log(f"CI 完成: [{name}] conclusion={conclusion}", "SUCCESS" if conclusion == "success" else "ERROR")
                    return run
                else:
                    self.logger.log(f"CI 状态: [{name}] {status}...")
            else:
                self.logger.log("未找到 CI 运行，等待 push 触发...")

            time.sleep(interval)

        self.logger.log(f"等待 CI 超时 ({timeout}s)", "ERROR")
        return None

    def get_latest_commit_sha(self) -> Optional[str]:
        """获取远程仓库最新 commit SHA"""
        data = self._api_get(f"commits/{self.branch}")
        if data and "sha" in data:
            return data["sha"]
        return None


# ============================================================
# AI 后端（bun 模式）
# ============================================================
class AIBackend:
    def __init__(self, config: Dict, runner: CommandRunner, logger: Logger):
        self.config = config
        self.runner = runner
        self.logger = logger

    def fix(self, prompt: str, cwd: str) -> Tuple[bool, str]:
        raise NotImplementedError


class HahaBunBackend(AIBackend):
    """通过 bun 从源码运行 cc-haha CLI"""

    def fix(self, prompt: str, cwd: str) -> Tuple[bool, str]:
        src_dir = self.config["cc_haha_src"]
        timeout = self.config["timeout_fix"]

        if not Path(src_dir).exists():
            self.logger.log(f"源码目录不存在: {src_dir}", "ERROR")
            return False, f"Source dir not found: {src_dir}"

        bun_cmd = _find_bun()
        if not bun_cmd:
            self.logger.log("bun 不可用：无法在 PATH 或已知路径中找到 bun", "ERROR")
            return False, "bun is not available"

        self.logger.log(f"使用 bun: {bun_cmd}")

        escaped_prompt = prompt.replace('"', '\\"').replace('\n', '\\n')
        cmd = (
            f'{bun_cmd} run --cwd "{src_dir}" src/entrypoints/cli.tsx'
            f' -- --bare --no-session-persistence'
            f' --dangerously-skip-permissions'
            f' --add-dir "{cwd}"'
            f' --print "{escaped_prompt}"'
        )
        self.logger.log(f"AI 命令: bun run ... --add-dir {cwd} --print ...")

        rc, stdout, stderr = self.runner.run(cmd, cwd=src_dir, timeout=timeout)

        output = stdout or stderr
        if rc == 0 and output:
            self.logger.log(f"AI 返回 {len(output)} 字符的输出", "INFO")
            return True, output
        elif rc != 0:
            self.logger.log(f"AI 执行失败 (rc={rc}): {output[:500]}", "ERROR")
            return False, output
        else:
            self.logger.log("AI 返回空输出（可能已直接修改文件）", "WARN")
            return True, ""


# ============================================================
# 文件变更检测器（不依赖 git）
# ============================================================
class ChangeDetector:
    """检测项目中被修改的文件（基于文件修改时间戳，不依赖 git）"""

    def __init__(self, logger: Logger):
        self.logger = logger
        self._snapshot: Dict[str, float] = {}

    def take_snapshot(self, project_dir: str):
        """记录所有文件的修改时间戳快照"""
        self._snapshot = {}
        project_path = Path(project_dir)
        for file_path in project_path.rglob("*"):
            if file_path.is_file():
                rel = file_path.relative_to(project_path).as_posix()
                if self._is_ignored(rel):
                    continue
                try:
                    self._snapshot[rel] = file_path.stat().st_mtime
                except OSError:
                    pass
        self.logger.log(f"已记录 {len(self._snapshot)} 个文件的快照")

    @staticmethod
    def _is_ignored(rel_path: str) -> bool:
        """判断是否应忽略该文件"""
        ignored_patterns = [
            ".git/", "node_modules/", "__pycache__/", ".gradle/", ".idea/",
            "build/", "target/", ".bun/", ".turbo/", "dist/", ".next/",
            ".venv/", "venv/", ".vscode/", "bin/", "obj/", "out/",
            "*.class", "*.pyc", "*.o", "*.so", "*.dll", "*.dylib",
            "*.exe", "*.log", "*.lock", "changed_files.json",
        ]
        for pattern in ignored_patterns:
            if pattern.startswith("*"):
                if rel_path.endswith(pattern[1:]):
                    return True
            elif pattern.startswith("/"):
                if rel_path == pattern[1:] or rel_path.startswith(pattern[1:] + "/"):
                    return True
            else:
                if pattern in rel_path:
                    return True
        return False

    def get_changed_files(self, project_dir: str) -> List[Dict]:
        """
        检测自上次快照后被修改的文件
        返回: [{"path": "relative/path", "content": "file content"}, ...]
        """
        if not self._snapshot:
            self.logger.log("没有快照数据，无法检测变更", "WARN")
            return self._try_git_fallback(project_dir)

        project_path = Path(project_dir)
        changed = []

        for rel_path, old_mtime in self._snapshot.items():
            abs_path = project_path / rel_path
            if not abs_path.exists():
                continue
            try:
                new_mtime = abs_path.stat().st_mtime
                if abs(new_mtime - old_mtime) > 0.001:
                    try:
                        content = abs_path.read_text(encoding="utf-8", errors="replace")
                        changed.append({"path": rel_path, "content": content})
                        self.logger.log(f"  变更: {rel_path}")
                    except Exception as e:
                        self.logger.log(f"  读取失败: {rel_path} - {e}", "WARN")
            except OSError:
                pass

        if not changed:
            changed = self._check_new_files(project_dir)

        self.logger.log(f"检测到 {len(changed)} 个变更文件")
        return changed

    def _check_new_files(self, project_dir: str) -> List[Dict]:
        """检查是否有新文件（不在快照中）"""
        project_path = Path(project_dir)
        new_files = []
        snapshot_paths = set(self._snapshot.keys())

        for file_path in project_path.rglob("*"):
            if not file_path.is_file():
                continue
            rel = file_path.relative_to(project_path).as_posix()
            if self._is_ignored(rel):
                continue
            if rel not in snapshot_paths:
                try:
                    content = file_path.read_text(encoding="utf-8", errors="replace")
                    new_files.append({"path": rel, "content": content})
                    self.logger.log(f"  新文件: {rel}")
                except Exception as e:
                    self.logger.log(f"  读取新文件失败: {rel} - {e}", "WARN")

        return new_files

    def _try_git_fallback(self, project_dir: str) -> List[Dict]:
        """尝试用 git 作为后备"""
        try:
            r = subprocess.run(
                "git status --porcelain", shell=True, cwd=project_dir,
                capture_output=True, text=True, timeout=30,
            )
            if r.returncode == 0 and r.stdout:
                files = []
                for line in r.stdout.strip().split("\n"):
                    if line.strip():
                        status = line[:2]
                        filepath = line[3:].strip().strip('"')
                        if "D" not in status:
                            files.append({"path": filepath.replace("\\", "/"), "content": ""})
                result = []
                project_path = Path(project_dir)
                for f in files:
                    abs_path = project_path / f["path"]
                    if abs_path.exists() and abs_path.is_file():
                        try:
                            f["content"] = abs_path.read_text(encoding="utf-8", errors="replace")
                            result.append(f)
                        except Exception:
                            pass
                return result
        except Exception:
            pass
        return []

    def save_changed_files_json(self, changed_files: List[Dict], output_path: str):
        """将变更文件保存为 JSON 文件"""
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(changed_files, f, ensure_ascii=False, indent=2)
        self.logger.log(f"变更文件已保存: {output_path} ({len(changed_files)} 个文件)")


# ============================================================
# GitHub API 推送器
# ============================================================
class GitHubPusher:
    """通过 GitHub API 推送文件变更"""

    API_BASE = "https://api.github.com"

    def __init__(self, config: Dict, logger: Logger):
        self.repo = config["repo"]
        self.branch = config["branch"]
        self.token = config.get("github_token")
        self.logger = logger

    def can_push(self) -> bool:
        return self.token is not None

    def push_files(self, files: List[Dict], commit_message: str) -> bool:
        if not self.token:
            self.logger.log("无 GitHub token，无法通过 API 推送", "WARN")
            self.logger.log("请由 TRAE 通过 MCP push_files 工具推送", "WARN")
            return False

        self.logger.section("通过 GitHub API 推送文件")

        base_sha = self._get_branch_sha()
        if not base_sha:
            return False

        base_tree = self._get_commit_tree(base_sha)
        if not base_tree:
            return False

        blobs = []
        for f in files:
            blob_sha = self._create_blob(f["content"])
            if blob_sha:
                blobs.append({"path": f["path"], "mode": "100644", "type": "blob", "sha": blob_sha})
            else:
                self.logger.log(f"创建 blob 失败: {f['path']}", "ERROR")
                return False

        new_tree_sha = self._create_tree(base_tree, blobs)
        if not new_tree_sha:
            return False

        new_commit_sha = self._create_commit(base_sha, new_tree_sha, commit_message)
        if not new_commit_sha:
            return False

        if self._update_branch_ref(new_commit_sha):
            self.logger.log(f"推送成功: {len(files)} 个文件", "SUCCESS")
            return True
        else:
            return False

    def _api_request(self, method: str, endpoint: str, data: Optional[Dict] = None, retries: int = 3) -> Optional[Any]:
        url = f"{self.API_BASE}/repos/{self.repo}/{endpoint}"
        headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "User-Agent": "auto-fix-script",
            "Content-Type": "application/json",
        }
        body = json.dumps(data).encode("utf-8") if data else None

        for attempt in range(1, retries + 1):
            try:
                req = urllib.request.Request(url, headers=headers, data=body, method=method)
                with urllib.request.urlopen(req, timeout=30) as resp:
                    if resp.status in (200, 201):
                        return json.loads(resp.read().decode("utf-8"))
                    return None
            except Exception as e:
                if attempt < retries:
                    wait = min(attempt * 5, 15)
                    self.logger.log(f"API 请求重试 ({attempt}/{retries}): {e}", "WARN")
                    time.sleep(wait)
                else:
                    self.logger.log(f"API 请求失败 ({method} {endpoint}): {e}", "ERROR")
        return None

    def _get_branch_sha(self) -> Optional[str]:
        data = self._api_request("GET", f"branches/{self.branch}")
        if data and "commit" in data:
            sha = data["commit"]["sha"]
            self.logger.log(f"分支 {self.branch} 最新 commit: {sha[:12]}")
            return sha
        return None

    def _get_commit_tree(self, commit_sha: str) -> Optional[str]:
        data = self._api_request("GET", f"git/commits/{commit_sha}")
        if data and "tree" in data:
            return data["tree"]["sha"]
        return None

    def _create_blob(self, content: str) -> Optional[str]:
        data = self._api_request("POST", "git/blobs", {"content": content, "encoding": "utf-8"})
        if data and "sha" in data:
            return data["sha"]
        return None

    def _create_tree(self, base_tree: str, items: List[Dict]) -> Optional[str]:
        data = self._api_request("POST", "git/trees", {"base_tree": base_tree, "tree": items})
        if data and "sha" in data:
            return data["sha"]
        return None

    def _create_commit(self, parent_sha: str, tree_sha: str, message: str) -> Optional[str]:
        data = self._api_request("POST", "git/commits", {
            "message": message, "parents": [parent_sha], "tree": tree_sha,
        })
        if data and "sha" in data:
            sha = data["sha"]
            self.logger.log(f"创建 commit: {sha[:12]}")
            return sha
        return None

    def _update_branch_ref(self, commit_sha: str) -> bool:
        url = f"{self.API_BASE}/repos/{self.repo}/git/refs/heads/{self.branch}"
        headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "User-Agent": "auto-fix-script",
            "Content-Type": "application/json",
        }
        body = json.dumps({"sha": commit_sha, "force": False}).encode("utf-8")

        try:
            req = urllib.request.Request(url, headers=headers, data=body, method="PATCH")
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.status in (200, 201)
        except Exception as e:
            self.logger.log(f"更新分支引用失败: {e}", "ERROR")
            return False


# ============================================================
# 主循环
# ============================================================
class AutoFixLoop:
    def __init__(self, config: Dict):
        self.config = config
        self.logger = Logger(config["quiet"])
        self.runner = CommandRunner(self.logger, config["dry_run"])
        self.ci_checker = GitHubActionsChecker(config, self.logger)
        self.ai_backend = HahaBunBackend(config, self.runner, self.logger)
        self.change_detector = ChangeDetector(self.logger)
        self.pusher = GitHubPusher(config, self.logger)

        self.project_dir = config["project_dir"]
        self.max_iterations = config["max_iterations"]

    def _generate_prompt(self, iteration: int, error_info: str = "") -> str:
        """生成修复提示词"""
        if self.config["prompt"]:
            base = self.config["prompt"]
        else:
            base = "fix the compilation errors in this project"

        if error_info:
            truncated = error_info[-5000:] if len(error_info) > 5000 else error_info
            prompt = (
                f"The GitHub Actions CI build is failing.\n"
                f"Here are the error details:\n\n{truncated}\n\n"
                f"Please analyze the project and fix the compilation errors. "
                f"Focus on the root causes of the failures. "
                f"After fixing, summarize the changes you made."
            )
        elif iteration == 1:
            prompt = (
                f"Please analyze this project at {self.project_dir} and {base}. "
                f"Check the build configuration, source code, and dependencies. "
                f"After fixing, summarize the changes you made."
            )
        else:
            prompt = (
                f"The GitHub Actions CI build is still failing after previous fixes. "
                f"Please carefully review the project's build configuration, "
                f"check for any remaining issues, and fix them. "
                f"After fixing, summarize the changes you made."
            )

        self.logger.log(f"Prompt ({len(prompt)} chars): {prompt[:200]}...")
        return prompt

    def run(self) -> bool:
        """运行 GitHub CI 自动修复循环"""
        self.logger.section("GitHub CI 自动修复循环启动")
        self.logger.log(f"仓库: {self.config['repo']}", force=True)
        self.logger.log(f"分支: {self.config['branch']}", force=True)
        self.logger.log(f"项目目录: {self.project_dir}", force=True)
        self.logger.log(f"最大迭代: {self.max_iterations}", force=True)
        self.logger.log(f"Dry-run: {self.config['dry_run']}", force=True)
        self.logger.log(f"GitHub Token: {'有' if self.config.get('github_token') else '无（仅检查，推送由 TRAE 处理）'}", force=True)

        if self.config["check_only"]:
            self.logger.log("仅检查模式", force=True)

        # Step 0: 初始检查
        ci_result = self.ci_checker.check_ci_status()

        if ci_result["all_passed"]:
            self.logger.section("CI 全部通过！")
            self.logger.log("所有 GitHub Actions 运行均成功", "SUCCESS")
            return True

        if self.config["check_only"]:
            self.logger.log("仅检查模式，不进行修复", "INFO")
            return False

        # 如果有进行中的 CI，等待完成
        if ci_result.get("in_progress"):
            self.logger.log("CI 正在运行中，等待完成...", "WARN")
            latest_commit = ci_result.get("latest_commit", "")
            if latest_commit:
                result = self.ci_checker.wait_for_ci_completion(
                    latest_commit,
                    timeout=self.config["ci_timeout"],
                    interval=self.config["wait_interval"],
                )
                if result:
                    if result.get("conclusion") == "success":
                        self.logger.section("CI 运行完成，结果：成功！")
                        return True
                    else:
                        self.logger.log(f"CI 运行完成，结果：{result.get('conclusion')}", "ERROR")
                        ci_result = self.ci_checker.check_ci_status()

        # 主循环
        error_info = ci_result.get("error_info", "")

        for iteration in range(1, self.max_iterations + 1):
            self.logger.section(f"第 {iteration}/{self.max_iterations} 轮")

            # Step 1: 先拍快照
            self.logger.log("Step 0: 记录文件快照...", force=True)
            self.change_detector.take_snapshot(self.project_dir)

            # Step 2: AI 修复
            self.logger.log("Step 1: AI 修复代码...", force=True)
            prompt = self._generate_prompt(iteration, error_info)

            if not self.config["dry_run"]:
                fix_ok, fix_output = self.ai_backend.fix(prompt, self.project_dir)
                if fix_output:
                    self.logger.log(f"AI 输出摘要: {fix_output[:300]}")
            else:
                self.logger.log("[DRY-RUN] 跳过 AI 修复", "WARN")
                fix_ok = True

            # Step 3: 检测变更文件
            self.logger.log("Step 2: 检测变更文件...", force=True)
            changed_files = self.change_detector.get_changed_files(self.project_dir)

            if not changed_files:
                self.logger.log("没有文件变更，可能 AI 已修复或需要手动处理", "WARN")
                self.logger.log("重新检查 CI 状态...", force=True)
                ci_result = self.ci_checker.check_ci_status()
                if ci_result["all_passed"]:
                    self.logger.section("CI 全部通过！")
                    return True
                continue

            # Step 4: 推送变更
            self.logger.log("Step 3: 推送变更到 GitHub...", force=True)
            commit_msg = f"auto-fix(iter {iteration}): fix CI compilation errors"

            if self.pusher.can_push() and not self.config["dry_run"]:
                push_ok = self.pusher.push_files(changed_files, commit_msg)
                if not push_ok:
                    self.logger.log("推送失败", "ERROR")
                    break
            else:
                json_path = str(Path(self.project_dir) / "changed_files.json")
                self.change_detector.save_changed_files_json(changed_files, json_path)
                self.logger.section("变更文件已保存")
                self.logger.log(f"文件: {json_path}", force=True)

                if not self.config["dry_run"]:
                    self.logger.log("请由 TRAE 通过 MCP push_files 推送:", "WARN", force=True)
                    self.logger.log(f"  owner={self.config['repo'].split('/')[0]} repo={self.config['repo'].split('/')[1]}", force=True)
                    self.logger.log(f"  branch={self.config['branch']} message={commit_msg}", force=True)
                    self.logger.log(f"  files={json_path}", force=True)
                    self.logger.log("推送后重新运行此脚本检查 CI 状态", "INFO", force=True)
                    return False

            # Step 5: 等待 CI 完成
            self.logger.log("Step 4: 等待 GitHub Actions CI 完成...", force=True)

            if not self.config["dry_run"]:
                time.sleep(5)
                latest_sha = self.ci_checker.get_latest_commit_sha()
                if latest_sha:
                    ci_result = self.ci_checker.check_ci_status()
                    if ci_result.get("in_progress") or not ci_result.get("all_passed"):
                        run = self.ci_checker.wait_for_ci_completion(
                            latest_sha,
                            timeout=self.config["ci_timeout"],
                            interval=self.config["wait_interval"],
                        )
                        if run:
                            if run.get("conclusion") == "success":
                                self.logger.section(f"第 {iteration} 轮 CI 通过！")
                                return True
                            else:
                                self.logger.log(f"CI 失败: {run.get('conclusion')}", "ERROR")
                                ci_result = self.ci_checker.check_ci_status()
                                error_info = ci_result.get("error_info", "")
                        else:
                            self.logger.log("等待 CI 超时", "ERROR")
                            break
                else:
                    self.logger.log("无法获取最新 commit SHA", "ERROR")
                    break
            else:
                self.logger.log("[DRY-RUN] 跳过 CI 等待", "WARN")

            self.logger.log(f"第 {iteration} 轮 CI 未通过，进入下一轮...", "WARN")

        self.logger.section("修复失败")
        self.logger.log(f"已达到最大迭代次数 ({self.max_iterations})，CI 仍未通过", "ERROR")
        return False


# ============================================================
# 命令行参数解析
# ============================================================
def parse_args() -> Dict:
    config = DEFAULT_CONFIG.copy()

    i = 1
    while i < len(sys.argv):
        arg = sys.argv[i]

        if arg in ("--help", "-h"):
            print(__doc__)
            sys.exit(0)
        elif arg == "--repo" and i + 1 < len(sys.argv):
            config["repo"] = sys.argv[i + 1]; i += 2
        elif arg == "--branch" and i + 1 < len(sys.argv):
            config["branch"] = sys.argv[i + 1]; i += 2
        elif arg == "--project-dir" and i + 1 < len(sys.argv):
            config["project_dir"] = sys.argv[i + 1]; i += 2
        elif arg == "--cc-haha-src" and i + 1 < len(sys.argv):
            config["cc_haha_src"] = sys.argv[i + 1]; i += 2
        elif arg == "--max-iterations" and i + 1 < len(sys.argv):
            config["max_iterations"] = int(sys.argv[i + 1]); i += 2
        elif arg == "--github-token" and i + 1 < len(sys.argv):
            config["github_token"] = sys.argv[i + 1]; i += 2
        elif arg == "--prompt" and i + 1 < len(sys.argv):
            config["prompt"] = sys.argv[i + 1]; i += 2
        elif arg == "--timeout-fix" and i + 1 < len(sys.argv):
            config["timeout_fix"] = int(sys.argv[i + 1]); i += 2
        elif arg == "--wait-interval" and i + 1 < len(sys.argv):
            config["wait_interval"] = int(sys.argv[i + 1]); i += 2
        elif arg == "--ci-timeout" and i + 1 < len(sys.argv):
            config["ci_timeout"] = int(sys.argv[i + 1]); i += 2
        elif arg == "--dry-run":
            config["dry_run"] = True; i += 1
        elif arg == "--quiet":
            config["quiet"] = True; i += 1
        elif arg == "--check-only":
            config["check_only"] = True; i += 1
        elif arg.startswith("--repo="):
            config["repo"] = arg.split("=", 1)[1]; i += 1
        elif arg.startswith("--github-token="):
            config["github_token"] = arg.split("=", 1)[1]; i += 1
        elif arg.startswith("--project-dir="):
            config["project_dir"] = arg.split("=", 1)[1]; i += 1
        elif arg.startswith("--max-iterations="):
            config["max_iterations"] = int(arg.split("=", 1)[1]); i += 1
        else:
            print(f"未知参数: {arg}")
            i += 1

    return config


# ============================================================
# 入口
# ============================================================
def main():
    config = parse_args()

    if not Path(config["project_dir"]).exists():
        print(f"错误: 项目目录不存在: {config['project_dir']}")
        sys.exit(1)

    loop = AutoFixLoop(config)
    success = loop.run()

    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()