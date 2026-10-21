#!/usr/bin/env python3
"""SC-08 independent libatspi checks. Starts/stops only its own --semantics fixture.
Usage: semantics_qa.py <installed-launcher> <new-output-directory>
"""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import platform
import subprocess
import time
import gi

gi.require_version('Atspi', '2.0')
from gi.repository import Atspi, GLib
from atspi_probe import find_applications, process_ids, libatspi_version


def pump():
    context = GLib.MainContext.default()
    for _ in range(256):
        if not context.pending():
            break
        context.iteration(False)


def settle():
    end = time.monotonic() + .2
    while time.monotonic() < end:
        pump()
        time.sleep(.01)


def tree(app):
    found = {}
    seen = set()
    pending = [app]
    while pending:
        node = pending.pop()
        if node in seen:
            continue
        seen.add(node)
        if len(seen) > 256:
            raise AssertionError('fixture tree bound')
        node.clear_cache()
        identity = node.get_accessible_id()
        name = node.get_name()
        if 'SC08_SECRET' in name or 'SC08_SECRET' in identity or 'password-id-must-not-publish' in identity or identity == 'sensitive-id':
            raise AssertionError('privacy metadata leak')
        if identity:
            if identity in found:
                raise AssertionError('duplicate public ID')
            found[identity] = node
        if node.get_text_iface() is not None:
            text = Atspi.Text.get_text(node, 0, -1)
            if 'SC08_SECRET' in text:
                raise AssertionError('privacy text leak')
        count = node.get_child_count()
        if count > 256:
            raise AssertionError('fixture child bound')
        pending.extend(node.get_child_at_index(i) for i in range(count))
    return found


def run(launcher, out):
    out.mkdir(parents=True, exist_ok=False)
    results = {'status': 'Failed', 'cases': [], 'os': platform.freedesktop_os_release().get('PRETTY_NAME'),
               'session': os.environ.get('XDG_SESSION_TYPE'), 'desktop': os.environ.get('XDG_CURRENT_DESKTOP'),
               'libatspi': libatspi_version(), 'python': platform.python_version(), 'pygobject': gi.__version__}
    Atspi.set_timeout(2000, 5000)
    environment = dict(os.environ)
    for variable in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'FX_LAB_OPTS'):
        environment.pop(variable, None)
    listener = None
    counts = Counter()
    events = [0]
    def record(event):
        events[0] += 1
        if events[0] <= 2048:
            counts[event.type.split(':')[1]] += 1
    with (out / 'fixture.log').open('w') as log:
        process = subprocess.Popen([str(launcher), '--semantics'], stdout=log, stderr=subprocess.STDOUT, env=environment)
        try:
            app = None
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline and app is None:
                if process.poll() is not None:
                    raise RuntimeError('fixture exited')
                matches = find_applications(Atspi.get_desktop(0), 'SC08 Semantic Lab', deadline)
                if matches:
                    pids = process_ids(matches, deadline)
                    if pids is not None:
                        app = next((value for value, pid in zip(matches, pids) if pid == process.pid), None)
                settle()
            if app is None:
                raise AssertionError('discovery timeout')
            listener = Atspi.EventListener.new(record)
            assert listener.register_with_app('object:', None, app)
            found = tree(app)
            expected = {'check', 'radio-a', 'radio-b', 'list', 'combo', 'tabs', 'tab-first', 'tab-second',
                        'first-content', 'menu-file', 'menu-execute', 'custom', 'result', 'rename', 'duplicate'}
            assert expected <= found.keys(), sorted(expected - found.keys())
            assert 'second-content' not in found
            assert found['check'].get_role() == Atspi.Role.CHECK_BOX
            assert Atspi.Action.get_action_name(found['check'], 0) == 'toggle'
            assert found['check'].get_attributes().get('automation-id') == 'check'
            results['cases'].append('discovery-roles-actions-attributes-privacy')
            assert Atspi.Action.do_action(found['check'], 0)
            settle()
            found = tree(app)
            assert found['check'].get_state_set().contains(Atspi.StateType.CHECKED)
            assert Atspi.Action.do_action(found['radio-b'], 0)
            settle()
            found = tree(app)
            assert found['radio-b'].get_state_set().contains(Atspi.StateType.CHECKED)
            assert not found['radio-a'].get_state_set().contains(Atspi.StateType.CHECKED)
            results['cases'].append('checked-radio-actions')
            assert Atspi.Selection.select_child(found['list'], 1)
            settle()
            found = tree(app)
            assert Atspi.Selection.get_n_selected_children(found['list']) == 1
            selected = Atspi.Selection.get_selected_child(found['list'], 0)
            assert selected == found['list'].get_child_at_index(1)
            assert Atspi.Selection.select_all(found['list'])
            settle()
            found = tree(app)
            assert Atspi.Selection.get_n_selected_children(found['list']) == 3
            assert Atspi.Selection.deselect_selected_child(found['list'], 1)
            settle()
            found = tree(app)
            assert Atspi.Selection.get_n_selected_children(found['list']) == 2
            assert Atspi.Selection.clear_selection(found['list'])
            settle()
            found = tree(app)
            assert Atspi.Selection.get_n_selected_children(found['list']) == 0
            results['cases'].append('multiple-selection-and-selected-index')
            assert Atspi.Text.get_text(found['combo'], 0, -1) == 'outside'
            assert Atspi.Selection.select_child(found['combo'], 1)
            settle()
            found = tree(app)
            assert Atspi.Text.get_text(found['combo'], 0, -1) == 'Blue'
            assert Atspi.Action.do_action(found['combo'], 0)
            settle()
            found = tree(app)
            assert found['combo'].get_state_set().contains(Atspi.StateType.EXPANDED)
            assert Atspi.Action.do_action(found['combo'], 0)
            results['cases'].append('combo-value-selection-popup')
            assert Atspi.Selection.select_child(found['tabs'], 1)
            settle()
            found = tree(app)
            assert 'second-content' in found and 'first-content' not in found
            results['cases'].append('tabs-and-active-content')
            try:
                Atspi.Action.do_action(found['menu-execute'], 0)
                raise AssertionError('closed menu action accepted')
            except GLib.Error:
                pass
            assert Atspi.Action.do_action(found['menu-file'], 0)
            settle()
            found = tree(app)
            assert Atspi.Action.do_action(found['menu-execute'], 0)
            settle()
            found = tree(app)
            assert found['result'].get_name() == 'Executed'
            assert Atspi.Action.do_action(found['menu-file'], 0)
            settle()
            found = tree(app)
            assert Atspi.Action.do_action(found['menu-check'], 0)
            settle()
            found = tree(app)
            assert found['menu-check'].get_state_set().contains(Atspi.StateType.CHECKED)
            results['cases'].append('closed-menu-denial-open-menu-and-checked-item')
            assert Atspi.Action.do_action(found['show-context'], 0)
            settle()
            found = tree(app)
            assert 'context-action' in found
            rectangle = Atspi.Component.get_extents(found['context-menu'], Atspi.CoordType.SCREEN)
            assert rectangle.width > 0 and rectangle.height > 0
            original_menu = found['context-menu']
            assert Atspi.Action.do_action(found['toggle-context-sensitive'], 0)
            settle()
            found = tree(app)
            assert 'context-menu' not in found and 'context-action' not in found
            assert found['result'].get_name() == 'Context showing: true'
            assert Atspi.Action.do_action(found['toggle-context-sensitive'], 0)
            settle()
            found = tree(app)
            assert found['context-menu'] != original_menu and 'context-action' in found
            assert found['result'].get_name() == 'Context showing: true'
            results['cases'].append('showing-context-menu-owner-privacy-recovery')
            assert Atspi.Action.do_action(found['context-action'], 0)
            results['cases'].append('context-menu')
            for suffix in ('combo', 'button', 'bar'):
                settle()
                found = tree(app)
                assert Atspi.Action.do_action(found['show-context-' + suffix], 0)
                settle()
                found = tree(app)
                assert 'context-' + suffix in found and 'context-item-' + suffix in found
                assert Atspi.Action.do_action(found['context-item-' + suffix], 0)
            results['cases'].append('independent-context-menus-on-adapted-controls')
            settle()
            found = tree(app)
            original_menu_button = found['menu-button']
            assert original_menu_button.get_name() == 'Menu button'
            assert Atspi.Action.get_n_actions(original_menu_button) == 1
            assert Atspi.Action.get_action_name(original_menu_button, 0) == 'toggle-popup'
            assert Atspi.Action.do_action(found['rename-menu'], 0)
            settle()
            found = tree(app)
            assert found['menu-button'] == original_menu_button and found['menu-button'].get_name() == 'Renamed menu'
            results['cases'].append('menu-button-dynamic-visible-label')
            split = found['split-button']
            assert split.get_name() == 'Run split'
            assert Atspi.Action.get_n_actions(split) == 2
            assert [Atspi.Action.get_action_name(split, i) for i in range(2)] == ['click', 'toggle-popup']
            assert Atspi.Action.do_action(split, 0)
            settle()
            found = tree(app)
            assert found['split-result'].get_name() == 'Primary 1'
            assert not found['split-button'].get_state_set().contains(Atspi.StateType.EXPANDED)
            for expanded in (True, False, True):
                assert Atspi.Action.do_action(found['split-button'], 1)
                settle()
                found = tree(app)
                assert found['split-button'].get_state_set().contains(Atspi.StateType.EXPANDED) == expanded
                assert found['split-result'].get_name() == 'Primary 1'
                assert app.get_child_count() == 1
            assert Atspi.Action.do_action(found['split-menu-item'], 0)
            settle()
            found = tree(app)
            assert found['split-result'].get_name() == 'Menu action'
            assert Atspi.Action.do_action(found['split-button'], 0)
            settle()
            found = tree(app)
            assert found['split-result'].get_name() == 'Primary 2'
            results['cases'].append('split-primary-popup-and-menu-actions')
            assert Atspi.Action.do_action(found['show-window-popup'], 0)
            settle()
            found = tree(app)
            assert 'window-popup' in found and 'window-popup-item' in found
            original_popup = found['window-popup']
            rectangle = Atspi.Component.get_extents(original_popup, Atspi.CoordType.SCREEN)
            assert rectangle.width > 0 and rectangle.height > 0
            assert Atspi.Action.do_action(found['toggle-window-popup-privacy'], 0)
            settle()
            found = tree(app)
            assert 'window-popup' not in found and 'window-popup-item' not in found
            assert found['result'].get_name() == 'Window popup showing: true'
            assert Atspi.Action.do_action(found['toggle-window-popup-privacy'], 0)
            settle()
            found = tree(app)
            assert found['window-popup'] != original_popup and 'window-popup-item' in found
            assert Atspi.Action.do_action(found['window-popup-item'], 0)
            settle()
            found = tree(app)
            assert found['result'].get_name() == 'Window popup command'
            assert app.get_child_count() == 1
            results['cases'].append('window-owned-popup-scene-root-privacy-recovery')
            assert Atspi.Text.get_text(found['editable-combo'], 0, -1) == 'a😀b'
            assert Atspi.Action.do_action(found['caret-two'], 0)
            settle()
            found = tree(app)
            assert Atspi.Text.get_caret_offset(found['editable-combo']) == 2
            assert Atspi.Action.do_action(found['caret-one'], 0)
            settle()
            found = tree(app)
            assert Atspi.Text.get_caret_offset(found['editable-combo']) == 1
            assert counts['text-caret-moved'] > 0
            results['cases'].append('editable-combo-unicode-caret-and-events')
            original_item = found['real-item']
            assert original_item.get_parent() == found['real-selection']
            assert found['real-source'].get_child_count() == 0
            assert Atspi.Action.do_action(found['toggle-real-ownership'], 0)
            settle()
            found = tree(app)
            assert found['real-item'] == original_item and original_item.get_parent() == found['real-source']
            assert found['real-selection'].get_child_count() == 0
            assert Atspi.Action.do_action(found['toggle-real-ownership'], 0)
            settle()
            found = tree(app)
            assert found['real-item'] == original_item and original_item.get_parent() == found['real-selection']
            assert found['real-source'].get_child_count() == 0
            results['cases'].append('semantic-ownership-and-physical-release-preserve-reference')
            assert Atspi.Selection.select_child(found['real-selection'], 0)
            assert Atspi.Action.do_action(found['toggle-source-visible'], 0)
            settle()
            found = tree(app)
            for method, args in ((Atspi.Selection.select_child, (0,)), (Atspi.Selection.select_all, ()), (Atspi.Selection.clear_selection, ())):
                try:
                    method(found['real-selection'], *args)
                    raise AssertionError('hidden real child selection accepted')
                except GLib.Error:
                    pass
            assert Atspi.Action.do_action(found['toggle-source-visible'], 0)
            settle()
            found = tree(app)
            assert Atspi.Selection.clear_selection(found['real-selection'])
            results['cases'].append('real-node-selection-physical-visibility')
            assert Atspi.Action.do_action(found['toggle-source-sensitive'], 0)
            settle()
            found = tree(app)
            assert 'real-item' not in found
            protected = found['real-selection'].get_child_at_index(0)
            assert protected.get_name() == '[redacted]'
            for method in (Atspi.Selection.select_all, Atspi.Selection.clear_selection):
                try:
                    method(found['real-selection'])
                    raise AssertionError('protected real child bulk selection accepted')
                except GLib.Error:
                    pass
            assert Atspi.Action.do_action(found['toggle-source-sensitive'], 0)
            settle()
            found = tree(app)
            assert 'real-item' in found
            assert Atspi.Selection.get_n_selected_children(found['real-selection']) == 0
            results['cases'].append('real-node-physical-ancestor-privacy-and-bulk-denial')
            original_item = found['real-item']
            for suffix, state in (('enabled', Atspi.StateType.ENABLED), ('showing', Atspi.StateType.SHOWING)):
                assert Atspi.Action.do_action(found['toggle-semantic-' + suffix], 0)
                settle()
                found = tree(app)
                assert found['real-item'] == original_item
                assert not found['real-item'].get_state_set().contains(state)
                for method, args in ((Atspi.Selection.select_child, (0,)), (Atspi.Selection.select_all, ()), (Atspi.Selection.clear_selection, ())):
                    try:
                        method(found['real-selection'], *args)
                        raise AssertionError('semantically unavailable selection accepted')
                    except GLib.Error:
                        pass
                assert Atspi.Action.do_action(found['toggle-semantic-' + suffix], 0)
                settle()
                found = tree(app)
                assert found['real-item'] == original_item and found['real-item'].get_state_set().contains(state)
                assert Atspi.Selection.select_child(found['real-selection'], 0)
                assert Atspi.Selection.clear_selection(found['real-selection'])
            results['cases'].append('real-node-inherits-semantic-availability-and-recovers')
            settle()
            found = tree(app)
            assert Atspi.Action.do_action(found['custom'], 0)
            settle()
            found = tree(app)
            assert Atspi.Text.get_text(found['custom'], 0, -1) == '1'
            original_counter = found['custom']
            assert Atspi.Action.do_action(found['replace-adapter'], 0)
            # updateOptions completes after provider adoption and an FX delivery, independently of DoAction's reply.
            deadline = time.monotonic() + 5
            while True:
                settle()
                found = tree(app)
                status = found['result'].get_name()
                if status == 'Adapters replaced':
                    break
                if status == 'Adapter update failed' or time.monotonic() >= deadline:
                    raise AssertionError(f'adapter completion: {status!r}')
            assert found['custom'] != original_counter and Atspi.Text.get_text(found['custom'], 0, -1) == '1'
            results['cases'].append('equal-adapter-instance-replacement-renews-reference')
            assert Atspi.Action.do_action(found['remove-custom'], 0)
            settle()
            found = tree(app)
            assert 'custom' not in found
            results['cases'].append('external-adapter-action-and-removal')
            original = found['check']
            assert Atspi.Action.do_action(found['rename'], 0)
            settle()
            found = tree(app)
            assert 'check' not in found and found['check-renamed'] == original
            results['cases'].append('id-change-preserves-reference')
            settle()
            assert events[0] <= 2048
            assert counts['state-changed'] > 0 and counts['selection-changed'] > 0 and counts['children-changed'] > 0
            results['events'] = dict(counts)
            results['cases'].append('state-selection-children-events')
            try:
                Atspi.Action.do_action(found['duplicate'], 0)
            except GLib.Error:
                pass  # Shutdown may answer the outstanding invocation with Provider stopped.
            settle()
            assert 'duplicate automation ID' in (out / 'fixture.log').read_text()
            matches = find_applications(Atspi.get_desktop(0), 'SC08 Semantic Lab', time.monotonic() + 5)
            assert not matches
            results['cases'].append('live-collision-stops-provider')
            results['status'] = 'Passed'
        except Exception as failure:
            results['error'] = str(failure)[:512]
            raise
        finally:
            if listener is not None:
                listener.deregister('object:')
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('launcher', type=Path)
    parser.add_argument('out', type=Path)
    args = parser.parse_args()
    run(args.launcher.resolve(), args.out.resolve())
