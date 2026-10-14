"""Pure assertion logic for the SC-03 desktop smoke (no desktop, bus or Java needed).

`smoke.py` gathers an observations dict from the real accessibility bus and hands it to `finish`, which turns every
critical observation into an executable check. Messages never echo tree text or the password secret: they name the
check and, for tree differences, the path and property, never the value.
"""
import json

SECRET = 'hunter2'
PREFIX = 'org.a11y.atspi.'
STATE_ENABLED = 8
MASKED_STATES = {1, 12}  # ACTIVE and FOCUSED follow window focus, not provider correctness
LABEL_NAMES = {'idle', 'fired'}  # the only name the action test intentionally changes
EXPECTED_SHAPE = [  # (name, role, interfaces) in depth-first order; the label name is normalised to '<label>'
    ('JavaFX AT-SPI Lab', 'application', ('Accessible', 'Application')),
    ('SmokeApp', 'frame', ('Accessible',)),
    ('', 'panel', ('Accessible',)),
    ('<label>', 'label', ('Accessible',)),
    ('Go', 'push button', ('Accessible', 'Action')),
    ('Off', 'push button', ('Accessible', 'Action')),
    ('field', 'entry', ('Accessible', 'EditableText', 'Text')),
    ('', 'text', ('Accessible', 'EditableText', 'Text')),
    ('pw', 'entry', ('Accessible',)),
]
UNICODE_TEXT = 'new\U0001F9EA'  # "new" followed by U+1F9EA TEST TUBE: 4 code points, 5 UTF-16 units


def flat(node):
    yield node
    for child in node.get('children', []):
        yield from flat(child)


def short(interfaces):
    return tuple(sorted(i.removeprefix(PREFIX) for i in interfaces))


def shape(tree):
    return [('<label>' if n['name'] in LABEL_NAMES and n['role'] == 'label' else n['name'], n['role'], short(n['interfaces'])) for n in flat(tree)]


def find(tree, name, role=None):
    return next((n for n in flat(tree) if n['name'] == name and (role is None or n['role'] == role)), None)


def differences(left, right, path='root'):
    """Property names (never values) that differ between two raw trees, with the path of the node."""
    found = []
    for key in ('role', 'interfaces', 'states', 'text'):
        if left.get(key) != right.get(key): found.append(f'{path}: {key}')
    left_name = '<label>' if left['role'] == 'label' and left['name'] in LABEL_NAMES else left['name']
    right_name = '<label>' if right['role'] == 'label' and right['name'] in LABEL_NAMES else right['name']
    if left_name != right_name: found.append(f'{path}: name')
    if len(left['children']) != len(right['children']):
        found.append(f'{path}: child count')
    else:
        for index, (a, b) in enumerate(zip(left['children'], right['children'])):
            found += differences(a, b, f'{path}/{index}')
    return found


def checks(obs):
    """Yield (name, passed, message) for every critical observation; a missing observation fails its check."""
    def has(*keys): return all(obs.get(k) is not None for k in keys)
    errors = obs.get('errors') or []
    yield 'run_completed', not errors, f'smoke phases raised: {len(errors)} (see results.json errors)'
    yield 'registered', bool(obs.get('registered_names')), 'provider never appeared in the real registry'
    before = obs.get('raw_before')
    yield 'tree_structure', bool(before) and shape(before) == EXPECTED_SHAPE, 'accessible tree structure differs from the expected one'
    go, off = (find(before, 'Go', 'push button'), find(before, 'Off', 'push button')) if before else (None, None)
    yield 'button_states', bool(go and off) and STATE_ENABLED in go['states'] and STATE_ENABLED not in off['states'], 'Go must be enabled and Off must not be'
    password = find(before, 'pw', 'entry') if before else None
    yield 'password_interfaces', bool(password) and short(password['interfaces']) == ('Accessible',), 'password node exposes Text or EditableText'
    serialized = json.dumps([obs.get('atspi_tree'), before, obs.get('raw_after')], ensure_ascii=False)
    yield 'password_secret_absent', has('atspi_tree') and SECRET not in serialized, 'password secret appears in the accessible tree'
    acts = obs.get('acts') or {}
    yield 'password_read_rejected', str(acts.get('pw_get_text', '')).startswith('error'), 'reading the password field was not rejected'
    yield 'password_write_rejected', str(acts.get('pw_set_text', '')).startswith('error'), 'writing the password field was not rejected'
    yield 'do_action', acts.get('do_action_go') is True, 'DoAction on Go did not return true'
    label = find(before, 'fired', 'label') if before else None
    yield 'do_action_effect', label is not None, 'the label did not change to its fired state after DoAction'
    yield 'set_text_unicode', acts.get('set_text_field_unicode') is True, 'SetTextContents with Unicode did not return true'
    field = find(before, 'field', 'entry') if before else None
    text = (field or {}).get('text') or {}
    yield 'unicode_roundtrip', text.get('text') == UNICODE_TEXT, 'the field did not read back the Unicode text that was set'
    yield 'unicode_code_points', text.get('count') == len(UNICODE_TEXT), 'character count is not in code points'
    immediate = obs.get('immediate_reinstall') or ''
    yield 'reinstall_rejected', immediate.startswith('REINSTALL_REJECTED:') and 'still terminating' in immediate, (
        'install was not rejected while shutdown was in progress' + (' (termination finished before the attempt; rerun)' if 'NOT_REJECTED' in immediate else ''))
    yield 'closed_completed', obs.get('closed_completed') is True, 'closed() never completed'
    yield 'old_provider_unlisted', obs.get('old_listed_after_close') is False, 'old provider is still listed by the registry after close'
    yield 'old_provider_uncallable', obs.get('old_provider_answers') is False, 'old provider still answers calls after close'
    yield 'reinstalled', obs.get('reinstalled') is True, 'reinstall after closed() did not succeed'
    second, old = obs.get('second_names') or [], obs.get('old_name')
    yield 'new_bus_name', bool(second) and old not in second, 'reinstalled provider did not register under a new bus name'
    after = obs.get('raw_after')
    different = differences(before, after) if before and after else ['no tree']
    yield 'reinstalled_tree', not different, 'reinstalled tree differs from the original at: ' + '; '.join(different[:5])
    yield 'exit_code', obs.get('exit_code') == 0, 'application exit code was not 0'
    yield 'registry_clean', obs.get('registry_clean_after_exit') is True, 'a provider is still listed for the process after exit'


def finish(obs):
    """Returns (result, exit_code, assertions); any failed check makes the result 'failed: ...' and the exit code 1."""
    assertions = [{'check': name, 'passed': bool(passed), 'message': None if passed else message} for name, passed, message in checks(obs)]
    failed = [a['check'] for a in assertions if not a['passed']]
    return ('completed' if not failed else 'failed: ' + ', '.join(failed)), (0 if not failed else 1), assertions
