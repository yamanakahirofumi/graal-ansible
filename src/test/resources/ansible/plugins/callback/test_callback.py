from ansible.plugins.callback import CallbackBase

class CallbackModule(CallbackBase):
    CALLBACK_VERSION = 2.0
    CALLBACK_TYPE = 'stdout'
    CALLBACK_NAME = 'test_callback'

    def __init__(self):
        super(CallbackModule, self).__init__()
        self.events = []

    def v2_playbook_on_start(self, playbook):
        print("PYTHON_CALLBACK: playbook_on_start")

    def v2_playbook_on_play_start(self, play):
        print(f"PYTHON_CALLBACK: play_start: {play.name}")

    def v2_playbook_on_task_start(self, task, is_conditional):
        print(f"PYTHON_CALLBACK: task_start: {task.name}")

    def v2_runner_on_ok(self, result):
        print(f"PYTHON_CALLBACK: runner_on_ok: {result._host.name}")

    def v2_playbook_on_stats(self, stats):
        print("PYTHON_CALLBACK: playbook_on_stats")
