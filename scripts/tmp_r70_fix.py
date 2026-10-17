from pathlib import Path
import re

controller_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
controller = controller_path.read_text()
controller, removed_calls = re.subn(
    r'^\s*tryPromoteNextSourceBoundary\(request\)\s*\n',
    '',
    controller,
    flags=re.M,
)
if removed_calls != 1:
    raise SystemExit(f'expected one remaining tryPromote call, got {removed_calls}')
controller_path.write_text(controller.rstrip('\n') + '\n')

policy_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicy.kt')
policy = policy_path.read_text()
policy, removed_selector = re.subn(
    r'''    fun selectNextSourceBoundary\(.*?(?=    data class VerifiedNextSourceBoundary\()''',
    '',
    policy,
    count=1,
    flags=re.S,
)
if removed_selector != 1:
    raise SystemExit(f'expected one stale next-source selector, got {removed_selector}')
policy_path.write_text(policy.rstrip('\n') + '\n')
