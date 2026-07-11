import json
import sys
import os
import ansible_bridge
import types
from typing import Any, Dict, List, Optional, Union, TYPE_CHECKING

if TYPE_CHECKING:
    # Injected by GraalVM context
    callback_name: str
    site_packages_java: Any
    collection_paths_java: Any

_callback_plugin: Any = None

def initialize_callback() -> None:
    global _callback_plugin
    site_packages: List[str] = [str(s) for s in site_packages_java] if 'site_packages_java' in globals() and site_packages_java is not None else []
    collection_paths: List[str] = [str(s) for s in collection_paths_java] if 'collection_paths_java' in globals() and collection_paths_java is not None else []

    ansible_bridge.setup_sys_path(site_packages, collection_paths)
    _callback_plugin = ansible_bridge._create_callback_plugin(callback_name)

def _wrap_result(host: str, result_java: Any) -> Any:
    # Mock TaskResult object for Python callbacks
    res = types.SimpleNamespace()
    res._host = types.SimpleNamespace()
    res._host.get_name = lambda: host
    res._host.name = host

    # deep_convert result data
    res._result = ansible_bridge._deep_convert(result_java.data())
    res._result['changed'] = result_java.changed()
    if not result_java.success():
        res._result['failed'] = True
        if result_java.message():
            res._result['msg'] = result_java.message()

    # Mock Task object inside result if possible
    res._task = types.SimpleNamespace()
    res._task.get_name = lambda: "mock_task"
    res._task.action = "unknown"

    return res

def v2_playbook_on_start(playbook_java: Any) -> None:
    if hasattr(_callback_plugin, 'v2_playbook_on_start'):
        # Simplified playbook mock
        pb = types.SimpleNamespace()
        _callback_plugin.v2_playbook_on_start(pb)

def v2_playbook_on_play_start(play_java: Any) -> None:
    if hasattr(_callback_plugin, 'v2_playbook_on_play_start'):
        p = types.SimpleNamespace()
        p.get_name = lambda: str(play_java.name())
        p.name = str(play_java.name())
        _callback_plugin.v2_playbook_on_play_start(p)

def v2_playbook_on_task_start(task_java: Any, is_conditional: bool) -> None:
    if hasattr(_callback_plugin, 'v2_playbook_on_task_start'):
        t = types.SimpleNamespace()
        t.get_name = lambda: str(task_java.name())
        t.name = str(task_java.name())
        t.action = str(task_java.action())
        _callback_plugin.v2_playbook_on_task_start(t, is_conditional)

def v2_runner_on_ok(host: str, result_java: Any) -> None:
    if hasattr(_callback_plugin, 'v2_runner_on_ok'):
        _callback_plugin.v2_runner_on_ok(_wrap_result(host, result_java))

def v2_runner_on_failed(host: str, result_java: Any, ignore_errors: bool) -> None:
    if hasattr(_callback_plugin, 'v2_runner_on_failed'):
        _callback_plugin.v2_runner_on_failed(_wrap_result(host, result_java), ignore_errors=ignore_errors)

def v2_runner_on_skipped(host: str, result_java: Any) -> None:
    if hasattr(_callback_plugin, 'v2_runner_on_skipped'):
        _callback_plugin.v2_runner_on_skipped(_wrap_result(host, result_java))

def v2_runner_on_unreachable(host: str, result_java: Any) -> None:
    if hasattr(_callback_plugin, 'v2_runner_on_unreachable'):
        _callback_plugin.v2_runner_on_unreachable(_wrap_result(host, result_java))

def v2_playbook_on_handler_stats(handler_name: str) -> None:
    if hasattr(_callback_plugin, 'v2_playbook_on_handler_stats'):
        _callback_plugin.v2_playbook_on_handler_stats(handler_name)

def v2_playbook_on_stats(stats_java: Any) -> None:
    if hasattr(_callback_plugin, 'v2_playbook_on_stats'):
        stats = ansible_bridge._deep_convert(stats_java)
        # Mocking the SummarizedStats if needed, but usually it's just a dict of dicts
        _callback_plugin.v2_playbook_on_stats(stats)

# Initialize when loaded
if 'callback_name' in globals():
    initialize_callback()
