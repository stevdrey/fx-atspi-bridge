import sys, json, time, gi
gi.require_version('Atspi','2.0')
from gi.repository import Atspi
pid=int(sys.argv[1]); out=sys.argv[2]
def find():
    d=Atspi.get_desktop(0)
    for i in range(d.get_child_count()):
        a=d.get_child_at_index(i)
        try:
            if a.get_process_id()==pid: return a
        except Exception: pass
app=None
for _ in range(60):
    app=find()
    if app and app.get_child_count()>0: break
    time.sleep(0.5)
assert app, "app not found"
def dump(n,depth=0):
    r={'name':n.get_name(),'role':n.get_role_name(),'roleEnum':n.get_role().value_nick,
       'ifaces':sorted(Atspi.Accessible.get_interfaces(n)),
       'states':sorted(s.value_nick for s in n.get_state_set().get_states()),'children':[]}
    ifs=r['ifaces']
    if 'Text' in ifs:
        t=n.get_text_iface() if hasattr(n,'get_text_iface') else n
        c=Atspi.Text.get_character_count(n); r['count']=c
        r['text']=Atspi.Text.get_text(n,0,c); r['caret']=Atspi.Text.get_caret_offset(n)
    if 'Action' in ifs: r['nactions']=Atspi.Action.get_n_actions(n)
    for i in range(n.get_child_count()): r['children'].append(dump(n.get_child_at_index(i),depth+1))
    return r
tree=dump(app)
def walk(n):
    yield n
    for c in n['children']: yield from walk(c)
acts={}
def nodes(n):
    yield n
    for i in range(n.get_child_count()): yield from nodes(n.get_child_at_index(i))
live=list(nodes(app))
for n in live:
    ifs=Atspi.Accessible.get_interfaces(n)
    if 'Action' in ifs and n.get_name()=='Go': acts['do_action']=Atspi.Action.do_action(n,0)
    if 'EditableText' in ifs and n.get_name()=='field':
        acts['set_text_field']=Atspi.EditableText.set_text_contents(n,'new🧪')
    if n.get_name()=='pw':
        try: acts['pw_set_text']=Atspi.EditableText.set_text_contents(n,'x')
        except Exception as e: acts['pw_set_text']='error'
        try: acts['pw_get_text']=Atspi.Text.get_text(n,0,-1)
        except Exception as e: acts['pw_get_text']='error'
time.sleep(1.5)
acts['after']=[ (n.get_name(), Atspi.Text.get_text(n,0,-1) if 'Text' in Atspi.Accessible.get_interfaces(n) else None) for n in nodes(app) if n.get_role_name() in('label','entry')]
json.dump({'tree':tree,'acts':acts},open(out,'w'),indent=1,ensure_ascii=False)
print('dumped',sum(1 for _ in walk(tree)),'nodes')
