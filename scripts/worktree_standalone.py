#!/usr/bin/env python3
"""
Standalone worktree workflow scripts for manual development.

Three independent workflows:
1. create: Create worktree and launch interactive CLI tool
2. export: Export worktree changes to review branch
3. cleanup: Remove worktree

Usage examples:
    # Create worktree and launch codex
    python3 scripts/worktree_standalone.py create /path/to/repo "codex --yolo"

    # Export changes to review branch
    python3 scripts/worktree_standalone.py export /path/to/repo /path/.worktrees/20250101-123456-abc12345

    # Cleanup worktree
    python3 scripts/worktree_standalone.py cleanup /path/to/repo /path/.worktrees/20250101-123456-abc12345
"""

import os
import sys
import subprocess
import argparse
from pathlib import Path
from datetime import datetime
import uuid
import shutil


def run_cmd(cmd, cwd=None, check=True, capture=True):
    """Run a shell command."""
    if capture:
        return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, check=check)
    else:
        return subprocess.run(cmd, cwd=cwd, check=check)


def create_and_launch(project_path: str, command: str, branch: str = "main"):
    """
    Create a worktree and launch an interactive CLI tool.

    Args:
        project_path: Path to the main git repository
        command: Command to launch (e.g., "codex --yolo")
        branch: Base branch to create worktree from (default: main)

    Returns:
        Path to the created worktree
    """
    project_path = Path(project_path).resolve()
    if not project_path.exists():
        print(f"Error: Project path does not exist: {project_path}")
        sys.exit(1)

    # Generate unique ID for this worktree
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    unique_id = f"{timestamp}-{uuid.uuid4().hex[:8]}"

    # Create worktree in parent/.worktrees/unique_id
    worktree_parent = project_path.parent
    worktrees_dir = worktree_parent / ".worktrees"
    worktrees_dir.mkdir(exist_ok=True)
    worktree_path = worktrees_dir / unique_id

    # Create branch name
    branch_name = f"work-{unique_id}"

    print(f"Creating worktree at: {worktree_path}")
    print(f"Branch: {branch_name}")
    print(f"Based on: {branch}")

    try:
        # Create the worktree
        cmd = [
            'git', '-C', str(project_path),
            'worktree', 'add',
            str(worktree_path),
            '-b', branch_name,
            branch
        ]
        result = run_cmd(cmd)
        print(f"✓ Worktree created successfully")

        # Copy local.properties so Gradle resolves local SDKs and keys
        try:
            src_local_props = project_path / "local.properties"
            dest_local_props = worktree_path / "local.properties"
            if src_local_props.exists():
                # Only copy if it doesn't already exist or source is newer
                if not dest_local_props.exists() or src_local_props.stat().st_mtime > dest_local_props.stat().st_mtime:
                    shutil.copy2(src_local_props, dest_local_props)
                    print("✓ Copied local.properties into worktree")
            else:
                print("ℹ local.properties not found in project root; skipping copy")
        except Exception as e:
            print(f"⚠ Failed to copy local.properties: {e}")

        # Copy Gradle wrapper so builds work in the worktree
        try:
            # Copy gradlew scripts
            for wrapper_file in ["gradlew", "gradlew.bat"]:
                src_wrapper = project_path / wrapper_file
                dest_wrapper = worktree_path / wrapper_file
                if src_wrapper.exists():
                    shutil.copy2(src_wrapper, dest_wrapper)

            # Copy gradle/wrapper directory
            src_gradle_wrapper = project_path / "gradle" / "wrapper"
            dest_gradle_wrapper = worktree_path / "gradle" / "wrapper"
            if src_gradle_wrapper.exists():
                dest_gradle_wrapper.parent.mkdir(parents=True, exist_ok=True)
                if dest_gradle_wrapper.exists():
                    shutil.rmtree(dest_gradle_wrapper)
                shutil.copytree(src_gradle_wrapper, dest_gradle_wrapper)
                print("✓ Copied Gradle wrapper into worktree")
            else:
                print("ℹ Gradle wrapper not found in project root; skipping copy")
        except Exception as e:
            print(f"⚠ Failed to copy Gradle wrapper: {e}")

        # Launch the interactive tool
        print(f"\nLaunching: {command}")
        print(f"Working directory: {worktree_path}")
        print("-" * 60)

        # Parse command into parts
        cmd_parts = command.split()

        # Launch without capturing output (interactive mode)
        run_cmd(cmd_parts, cwd=str(worktree_path), check=False, capture=False)

        print("-" * 60)
        print(f"\n✓ CLI tool exited")
        print(f"\nWorktree path: {worktree_path}")
        print(f"To export changes: python3 scripts/worktree_standalone.py export {project_path} {worktree_path}")
        print(f"To cleanup: python3 scripts/worktree_standalone.py cleanup {project_path} {worktree_path}")

        return str(worktree_path)

    except subprocess.CalledProcessError as e:
        print(f"Error creating worktree: {e.stderr if e.stderr else e}")
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}")
        sys.exit(1)


def export_to_review(project_path: str, worktree_path: str, auto_commit: bool = True):
    """
    Export worktree changes to a review branch in the main repository.

    Args:
        project_path: Path to the main git repository
        worktree_path: Path to the worktree
        auto_commit: Whether to auto-commit uncommitted changes (default: True)

    Returns:
        Name of the created review branch
    """
    project_path = Path(project_path).resolve()
    worktree_path = Path(worktree_path).resolve()

    if not worktree_path.exists():
        print(f"Error: Worktree does not exist: {worktree_path}")
        sys.exit(1)

    # Extract unique ID from worktree path
    unique_id = worktree_path.name
    work_branch = f"work-{unique_id}"
    review_branch = f"review/{unique_id}"

    print(f"Exporting worktree: {worktree_path}")
    print(f"To review branch: {review_branch}")

    try:
        # Check for uncommitted changes
        status_result = run_cmd(['git', '-C', str(worktree_path), 'status', '--porcelain'])
        has_changes = bool(status_result.stdout.strip())

        if has_changes and auto_commit:
            print("Found uncommitted changes, auto-committing...")
            run_cmd(['git', '-C', str(worktree_path), 'add', '-A'])
            commit_msg = f"Auto-commit: Export to review at {datetime.now().isoformat()}"
            run_cmd(['git', '-C', str(worktree_path), 'commit', '-m', commit_msg])
            print("✓ Changes committed")
        elif has_changes:
            print("Warning: Uncommitted changes exist but auto-commit is disabled")

        # Get HEAD commit of worktree
        head_result = run_cmd(['git', '-C', str(worktree_path), 'rev-parse', 'HEAD'])
        head_commit = head_result.stdout.strip()

        # Ensure work branch exists in main repo
        check_result = run_cmd(
            ['git', '-C', str(project_path), 'rev-parse', '--verify', f'refs/heads/{work_branch}'],
            check=False
        )
        if check_result.returncode != 0:
            # Create work branch pointing to worktree HEAD
            run_cmd(['git', '-C', str(project_path), 'branch', work_branch, head_commit])
            print(f"✓ Created branch {work_branch}")
        else:
            # Update work branch to worktree HEAD
            try:
                run_cmd(['git', '-C', str(project_path), 'branch', '-f', work_branch, head_commit])
                print(f"✓ Updated branch {work_branch}")
            except subprocess.CalledProcessError as e:
                error_text = e.stderr or str(e)
                if "used by worktree" in error_text:
                    print(f"ℹ Skipped updating {work_branch}: branch is active in worktree {worktree_path}")
                else:
                    raise

        # Create/update review branch
        run_cmd(['git', '-C', str(project_path), 'branch', '-f', review_branch, head_commit])
        print(f"✓ Created/updated {review_branch} -> {head_commit[:8]}")

        # Show what changed
        log_result = run_cmd([
            'git', '-C', str(project_path),
            'log', '--oneline', f'main..{review_branch}', '--max-count=10'
        ])
        if log_result.stdout.strip():
            print(f"\nCommits in {review_branch}:")
            print(log_result.stdout)

        print(f"\n✓ Export complete!")
        print(f"Review branch: {review_branch}")
        print(f"To push: cd {project_path} && git push -u origin {review_branch}")
        print(f"To cleanup worktree: python3 scripts/worktree_standalone.py cleanup {project_path} {worktree_path}")

        return review_branch

    except subprocess.CalledProcessError as e:
        print(f"Error during export: {e.stderr if e.stderr else e}")
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}")
        sys.exit(1)


def cleanup_worktree(project_path: str, worktree_path: str, force: bool = False):
    """
    Remove a worktree and optionally its associated branches.

    Args:
        project_path: Path to the main git repository
        worktree_path: Path to the worktree to remove
        force: Force removal even with uncommitted changes
    """
    project_path = Path(project_path).resolve()
    worktree_path = Path(worktree_path).resolve()

    if not worktree_path.exists():
        print(f"Worktree does not exist: {worktree_path}")
        return

    # Extract unique ID from worktree path
    unique_id = worktree_path.name
    work_branch = f"work-{unique_id}"

    print(f"Removing worktree: {worktree_path}")

    try:
        # Check for uncommitted changes
        if not force:
            status_result = run_cmd(['git', '-C', str(worktree_path), 'status', '--porcelain'], check=False)
            if status_result.returncode == 0 and status_result.stdout.strip():
                print("Warning: Uncommitted changes detected!")
                print("Use --force to remove anyway, or export first with:")
                print(f"  python3 scripts/worktree_standalone.py export {project_path} {worktree_path}")
                sys.exit(1)

        # Remove the worktree
        cmd = ['git', '-C', str(project_path), 'worktree', 'remove', str(worktree_path)]
        if force:
            cmd.append('--force')

        run_cmd(cmd)
        print("✓ Worktree removed")

        # Try to remove the work branch (not the review branch)
        try:
            run_cmd(['git', '-C', str(project_path), 'branch', '-D', work_branch])
            print(f"✓ Removed branch {work_branch}")
        except subprocess.CalledProcessError:
            # Branch might not exist or might have unpushed commits
            pass

        print("\n✓ Cleanup complete!")

    except subprocess.CalledProcessError as e:
        print(f"Error during cleanup: {e.stderr if e.stderr else e}")
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}")
        sys.exit(1)


def main():
    """Main entry point with subcommand parsing."""
    parser = argparse.ArgumentParser(
        description="Standalone worktree workflow for manual development",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Create worktree and launch codex
  python3 scripts/worktree_standalone.py create /path/to/repo "codex --yolo"

  # Create worktree from specific branch and launch different tool
  python3 scripts/worktree_standalone.py create /path/to/repo "claude-code" --branch develop

  # Export changes to review branch
  python3 scripts/worktree_standalone.py export /path/to/repo /path/.worktrees/20250101-123456-abc12345

  # Cleanup worktree
  python3 scripts/worktree_standalone.py cleanup /path/to/repo /path/.worktrees/20250101-123456-abc12345

  # Force cleanup even with uncommitted changes
  python3 scripts/worktree_standalone.py cleanup /path/to/repo /path/.worktrees/20250101-123456-abc12345 --force
        """
    )

    subparsers = parser.add_subparsers(dest='command', help='Command to run')

    # Create subcommand
    create_parser = subparsers.add_parser('create', help='Create worktree and launch CLI tool')
    create_parser.add_argument('project_path', help='Path to main git repository')
    create_parser.add_argument('tool_command', help='Command to launch (e.g., "codex --yolo")')
    create_parser.add_argument('--branch', default='main', help='Base branch (default: main)')

    # Export subcommand
    export_parser = subparsers.add_parser('export', help='Export worktree to review branch')
    export_parser.add_argument('project_path', help='Path to main git repository')
    export_parser.add_argument('worktree_path', help='Path to worktree')
    export_parser.add_argument('--no-auto-commit', action='store_true',
                               help='Do not auto-commit uncommitted changes')

    # Cleanup subcommand
    cleanup_parser = subparsers.add_parser('cleanup', help='Remove worktree')
    cleanup_parser.add_argument('project_path', help='Path to main git repository')
    cleanup_parser.add_argument('worktree_path', help='Path to worktree')
    cleanup_parser.add_argument('--force', action='store_true',
                                help='Force removal even with uncommitted changes')

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    if args.command == 'create':
        create_and_launch(args.project_path, args.tool_command, args.branch)
    elif args.command == 'export':
        export_to_review(args.project_path, args.worktree_path,
                        auto_commit=not args.no_auto_commit)
    elif args.command == 'cleanup':
        cleanup_worktree(args.project_path, args.worktree_path, args.force)


if __name__ == '__main__':
    main()
