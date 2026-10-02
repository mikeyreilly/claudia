package com.quaxt.codingagent.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.ai.json.Json;
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One agent's explicitly owned JDI sessions. No debugger action is taken until a tool requests it. */
public final class DebugManager implements AutoCloseable {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> requests = new ConcurrentHashMap<>();
    private final Map<String, String> requestArguments = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private static final int MAX_SESSIONS = 8, MAX_EVENTS = 512, MAX_OUTPUT = 512, MAX_PAGE = 100,
        MAX_IDEMPOTENCY = 4096;
    private static final Set<String> EVENT_TYPES = Set.of("launch","attach","detach","exit","stop","resume",
        "breakpoint_resolved","breakpoint_pending","breakpoint_error","breakpoint_change","logpoint");
    private static final boolean NATIVE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    private static final class Failure extends RuntimeException {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }
    private static Failure fail(String code, String message) { return new Failure(code, message); }
    private static final class Breakpoint {
        String id, type, className, method, signature, path, field, exception, condition, log, policy, thread, hitPolicy;
        int line, hits, hitCount;
        boolean enabled = true, oneShot, caught, uncaught, access, modification;
        String pending = "No exact executable location loaded yet (class, source, debug symbols, or line unavailable)";
        final List<EventRequest> requests = new ArrayList<>();
        final List<Location> locations = new ArrayList<>();
    }
    private static final class Session {
        final String id; final VirtualMachine vm; final Path cwd; final boolean launched; final Process process;
        final long pid; String target;
        long runnerPid=-1;
        List<String> targetArguments=List.of(), jvmOptions=List.of(), environmentNames=List.of();
        String outputWarning;
        final Map<String, Breakpoint> breakpoints = new LinkedHashMap<>();
        final Map<String, ObjectReference> references = new HashMap<>();
        final ArrayDeque<ObjectNode> events = new ArrayDeque<>(), output = new ArrayDeque<>();
        long eventCursor, outputCursor, stopSequence;
        volatile String state = "running";
        String stopId, reason, breakpointId, failure, completionReason;
        ThreadReference stoppedThread; ExceptionEvent exceptionEvent; WatchpointEvent watchpointEvent;
        MethodExitEvent methodExitEvent; EventSet suspendedSet;
        boolean manuallySuspended, captureOutput;
        volatile boolean closed;
        List<String> extraSecrets=List.of(), knownSecrets=List.of();
        int exitCode = Integer.MIN_VALUE;
        int outputReaders;
        StepRequest stepRequest;
        Session(String id, VirtualMachine vm, Path cwd, boolean launched, Process process, long pid) {
            this.id=id; this.vm=vm; this.cwd=cwd; this.launched=launched; this.process=process; this.pid=pid;
        }
    }

    public ObjectNode call(String operation, ObjectNode arguments, Path cwd) {
        return call(operation, arguments, cwd, () -> false);
    }
    public synchronized ObjectNode call(String operation, ObjectNode arguments, Path cwd,
                                        java.util.function.BooleanSupplier cancelled) {
        ObjectNode args = arguments == null ? Json.MAPPER.createObjectNode() : arguments;
        String key = str(args,"idempotency_key",null);
        String replay = key == null || key.isBlank() ? null : operation + ":" + str(args,"session_id", "") + ":" + key;
        if (replay != null && requests.containsKey(replay)) {
            if(!args.toString().equals(requestArguments.get(replay)))
                return error("idempotency_conflict","Idempotency key was already used with different arguments");
            return requests.get(replay).deepCopy();
        }
        try {
            if (closed) throw fail("closed", "Debugger manager is closed");
            if (cancelled.getAsBoolean()) throw fail("cancelled", "Debug call cancelled before execution; target state was not changed");
            if (NATIVE) throw fail("unsupported", "JDI debugging is available in the JVM CLI only; run java -jar codingagent.jar");
            if(replay!=null && requests.size()>=MAX_IDEMPOTENCY)
                throw fail("limit","Debugger idempotency history is full; reset the agent runtime before further state changes");
            ObjectNode result = switch (operation) {
                case "debug_launch" -> launch(args,cwd);
                case "debug_attach" -> attach(args,cwd);
                case "debug_sessions" -> sessionList(args);
                default -> dispatch(operation,args,cancelled);
            };
            return remember(replay,args,result);
        } catch (Failure e) { return remember(replay,args,withSession(error(e.code,e.getMessage()),args)); }
        catch (VMDisconnectedException e) { return remember(replay,args,withSession(error("disconnected", "Target disconnected; inspect debug_status or debug_output"),args)); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return remember(replay,args,withSession(error("cancelled", "Debug wait interrupted; target execution was not paused or terminated"),args));
        }
        catch (Exception e) { return remember(replay,args,withSession(error("target_error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),args)); }
    }
    private ObjectNode remember(String replay,ObjectNode args,ObjectNode result) {
        if(replay!=null) { requests.put(replay,result.deepCopy()); requestArguments.put(replay,args.toString()); }
        return result;
    }
    private static ObjectNode node(String status, String summary) { return Json.MAPPER.createObjectNode().put("status",status).put("summary",summary); }
    private static ObjectNode error(String code, String message) { return node("error",message).put("code",code).put("message",message); }
    private static ObjectNode withSession(ObjectNode error,JsonNode args) {
        if(args.path("session_id").isTextual()) error.put("session_id",args.path("session_id").asText());
        if(args.path("stop_id").isTextual()) error.put("stop_id",args.path("stop_id").asText());
        return error;
    }
    private static String str(JsonNode n,String k,String def) { JsonNode v=n.get(k); return v!=null && v.isTextual() && !v.asText().isBlank()?v.asText():def; }
    private static int num(JsonNode n,String k,int def,int max) { JsonNode v=n.get(k); if (v==null || v.isNull()) return def; if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt()<0 || v.asInt()>max) throw fail("invalid_argument", k+" must be between 0 and "+max); return v.asInt(); }
    private static boolean bool(JsonNode n,String k,boolean def) { JsonNode v=n.get(k); return v==null?def:v.asBoolean(def); }
    private static int limit(JsonNode n) { int l=num(n,"limit",20,MAX_PAGE); return l==0?20:l; }
    private static int cursor(JsonNode n) { return num(n,"cursor",0,Integer.MAX_VALUE); }
    private static void required(String value,String name) { if (value==null || value.isBlank()) throw fail("invalid_argument",name+" is required"); }
    private static Path path(Path cwd,String text) { Path p=Path.of(text); return (p.isAbsolute()?p:cwd.resolve(p)).toAbsolutePath().normalize(); }

    private ObjectNode launch(ObjectNode a,Path cwd) throws Exception {
        String main=str(a,"main_class",null), test=str(a,"test_selector",null);
        if ((main==null)==(test==null)) throw fail("invalid_argument","Specify exactly one main_class or test_selector");
        if(main!=null && main.length()>512 || test!=null && test.length()>512)
            throw fail("invalid_argument","Java entry point/test selector is too long");
        if(test!=null && a.has("environment") && !a.path("environment").isEmpty())
            throw fail("unsupported","Maven test launch environment overrides are not supported; launch a JDWP-enabled test process and attach");
        if(test!=null && (str(a,"module",null)!=null || !strings(a,"jvm_options").isEmpty() || !strings(a,"classpath").isEmpty() || !strings(a,"module_path").isEmpty()))
            throw fail("unsupported","The Maven test launcher controls the fork JVM; custom JVM/classpath/module options are not supported");
        if(!strings(a,"source_roots").isEmpty()) throw fail("unsupported","Custom source_roots are not implemented; use sources in the Maven workspace");
        if(a.path("environment") instanceof ObjectNode overrides) {
            if(overrides.size()>64) throw fail("limit","At most 64 environment overrides are allowed");
            overrides.fields().forEachRemaining(entry->{
            if(!entry.getValue().isTextual() || entry.getKey().length()>128 || entry.getValue().asText().length()>8192)
                throw fail("invalid_argument","Environment key/value must be bounded strings");
            if(entry.getKey().matches("(?i).*(?:PASSWORD|PASS|SECRET|TOKEN|API_KEY|CREDENTIAL).*")
                && entry.getValue().asText().length()>2048 && bool(a,"capture_output",true))
                throw fail("unsupported","Sensitive environment override is too long to redact safely; use capture_output: false");
            });
        }
        if(!strings(a,"arguments").isEmpty() && !strings(a,"args").isEmpty()) throw fail("invalid_argument","Specify arguments or args, not both");
        if (sessions.values().stream().filter(s->!s.closed && !s.state.equals("completed")).count()>=MAX_SESSIONS || sessions.size()>=64)
            throw fail("limit", "Close old sessions with debug_detach close_session: true before launching another");
        Path work=path(cwd,str(a,"working_directory","."));
        if (!Files.isDirectory(work)) throw fail("invalid_argument","Working directory does not exist: "+work);
        VirtualMachine vm; Process process;
        long debuggeePid=-1;
        if (test!=null) {
            int port;
            try (ServerSocket reserved=new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) { port=reserved.getLocalPort(); }
            process=MavenTestLaunch.start(work,test,port);
            try {
                vm=connectSocket(port, Math.max(1000,num(a,"launch_timeout_ms",15000,120000)),process);
                List<ProcessHandle> forks=process.descendants().filter(handle -> handle.info().arguments()
                    .map(argv->Arrays.stream(argv).anyMatch(arg->arg.startsWith("-agentlib:jdwp=")
                        && arg.contains("address=127.0.0.1:"+port))).orElse(false)).toList();
                if (forks.isEmpty() && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                    // Windows commonly omits arguments from ProcessHandle.Info. Maven's
                    // JVM has one suspended Surefire JVM below it; identify that Java leaf.
                    List<ProcessHandle> javaProcesses = process.descendants()
                        .filter(handle -> handle.info().command()
                            .map(command -> command.toLowerCase(Locale.ROOT).endsWith("\\java.exe")
                                || command.toLowerCase(Locale.ROOT).endsWith("\\javaw.exe"))
                            .orElse(false)).toList();
                    forks = javaProcesses.stream().filter(handle -> javaProcesses.stream()
                        .anyMatch(other -> other.pid() != handle.pid()
                            && other.descendants().anyMatch(child -> child.pid() == handle.pid())))
                        .filter(handle -> javaProcesses.stream()
                            .noneMatch(other -> other.pid() != handle.pid()
                                && handle.descendants().anyMatch(child -> child.pid() == other.pid()))).toList();
                }
                if(forks.size()!=1) throw fail("ambiguous_target","Cannot uniquely identify the selected Surefire fork PID; candidates: "
                    +forks.stream().map(ProcessHandle::pid).toList());
                debuggeePid=forks.getFirst().pid();
            } catch (Exception e) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); throw e; }
        } else {
            if (!main.matches("[\\w$]+(?:\\.[\\w$]+)*")) throw fail("invalid_argument","main_class must be a fully qualified Java class name");
            int port;
            try(ServerSocket reserved=new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) { port=reserved.getLocalPort(); }
            List<String> command=new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString());
            command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:"+port);
            command.addAll(strings(a,"jvm_options"));
            List<String> cp=strings(a,"classpath"), mp=strings(a,"module_path");
            if(cp.isEmpty() && Files.isDirectory(work.resolve("target/classes"))) cp=List.of(work.resolve("target/classes").toString());
            if(!cp.isEmpty()) { command.add("-cp"); command.add(String.join(java.io.File.pathSeparator,cp)); }
            if(!mp.isEmpty()) { command.add("--module-path"); command.add(String.join(java.io.File.pathSeparator,mp)); }
            String module=str(a,"module",null);
            if(module!=null) {
                if(!module.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
                    throw fail("invalid_argument","module must be a Java module name");
                command.add("--module"); command.add(module+"/"+main);
            } else command.add(main);
            List<String> programArgs=strings(a,"arguments");
            if(programArgs.isEmpty()) programArgs=strings(a,"args");
            command.addAll(programArgs);
            ProcessBuilder builder=new ProcessBuilder(command).directory(work.toFile());
            if(a.path("environment") instanceof ObjectNode env) env.fields().forEachRemaining(entry->{
                if(!entry.getValue().isTextual()) throw fail("invalid_argument","Environment values must be strings");
                builder.environment().put(entry.getKey(),entry.getValue().asText());
            });
            process=builder.start(); debuggeePid=process.pid();
            try { vm=connectSocket(port,Math.max(1000,num(a,"launch_timeout_ms",15000,120000)),process); }
            catch(Exception e) { process.destroyForcibly(); throw e; }
        }
        String id=UUID.randomUUID().toString();
        Session s=new Session(id,vm,work,true,process,debuggeePid);
        if(test!=null) s.runnerPid=process.pid();
        s.target=main==null?"Maven/JUnit "+test:main;
        s.targetArguments=strings(a,"arguments").isEmpty()?strings(a,"args"):strings(a,"arguments");
        s.jvmOptions=strings(a,"jvm_options");
        if(a.path("environment") instanceof ObjectNode overrides) {
            List<String> names=new ArrayList<>(); overrides.fieldNames().forEachRemaining(names::add);
            s.environmentNames=List.copyOf(names);
        }
        s.captureOutput=bool(a,"capture_output",true);
        if(s.captureOutput) {
            List<String> sensitiveValues=System.getenv().entrySet().stream()
                .filter(e->e.getKey().matches("(?i).*(?:PASSWORD|PASS|SECRET|TOKEN|API_KEY|CREDENTIAL).*"))
                .map(Map.Entry::getValue).flatMap(v->Arrays.stream(v.split("\\R",-1)))
                .filter(v->!v.isEmpty()).distinct().toList();
            if(sensitiveValues.stream().anyMatch(v->v.length()>2048)) {
                s.captureOutput=false;
                s.outputWarning="Output capture disabled: a sensitive environment value exceeds the safe redaction limit";
            } else s.knownSecrets=sensitiveValues;
        }
        if(a.path("environment") instanceof ObjectNode env) {
            List<String> secrets=new ArrayList<>(); env.fields().forEachRemaining(e->{
                if(e.getKey().matches("(?i).*(?:PASSWORD|PASS|SECRET|TOKEN|API_KEY|CREDENTIAL).*")
                    && !e.getValue().asText().isEmpty())
                    for(String part:e.getValue().asText().split("\\R",-1)) if(!part.isEmpty()) secrets.add(part);
            });
            s.extraSecrets=List.copyOf(secrets);
        }
        sessions.put(id,s);
        ObjectNode launchEvent=Json.MAPPER.createObjectNode().put("target",s.target).put("pid",s.pid);
        if(s.runnerPid>0) launchEvent.put("runner_pid",s.runnerPid);
        event(s,"launch",launchEvent);
        boolean stopOnEntry=bool(a,"stop_on_entry",false);
        initialize(s,stopOnEntry);
        if(stopOnEntry) synchronized(s) {
            long deadline=System.nanoTime()+1_000_000_000L;
            while(s.state.equals("running") && System.nanoTime()<deadline) try {
                s.wait(Math.max(1,(deadline-System.nanoTime())/1_000_000L));
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        return status(s,"Launched "+(main==null?test:main)+"; target arguments were explicitly selected");
    }
    private static List<String> strings(JsonNode a,String name) {
        JsonNode v=a.path(name); if (!v.isArray()) return List.of();
        if(v.size()>64) throw fail("limit",name+" may contain at most 64 values");
        List<String> result=new ArrayList<>(); for(JsonNode item:v) {
            if(!item.isTextual() || item.asText().length()>8192) throw fail("invalid_argument",name+" must contain strings of at most 8192 characters");
            result.add(item.asText());
        }
        return result;
    }
    private VirtualMachine connectSocket(int port,int timeout,Process process) throws Exception {
        AttachingConnector c=Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(x->x.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow(()->fail("unsupported","SocketAttach connector unavailable"));
        Map<String,Connector.Argument> args=c.defaultArguments(); args.get("hostname").setValue("127.0.0.1"); args.get("port").setValue(Integer.toString(port));
        args.get("timeout").setValue("1000"); long end=System.nanoTime()+timeout*1_000_000L;
        Exception last=null;
        while(System.nanoTime()<end) {
            if(process!=null && !process.isAlive()) break;
            try { return c.attach(args); } catch(IOException | IllegalConnectorArgumentsException ex) { last=ex; Thread.sleep(75); }
        }
        throw fail("timeout","JDWP target was not available before launch timeout"+(last==null?"":": "+last.getMessage()));
    }
    private ObjectNode attach(ObjectNode a,Path cwd) throws Exception {
        if (!bool(a,"consent",false)) throw fail("permission_required","debug_attach requires consent: true");
        if (str(a,"target_selector",null)!=null) throw fail("unsupported","Use an explicit PID; target selectors are not yet supported");
        if(!strings(a,"source_roots").isEmpty() || a.has("path_mappings") && !a.path("path_mappings").isEmpty())
            throw fail("unsupported","Custom attach source_roots and path_mappings are not implemented; use sources under the current workspace");
        int pid=num(a,"pid",0,Integer.MAX_VALUE); if(pid<=0) throw fail("invalid_argument","pid is required");
        if (sessions.values().stream().filter(s->!s.closed && !s.state.equals("completed")).count()>=MAX_SESSIONS || sessions.size()>=64)
            throw fail("limit","Close old sessions with debug_detach close_session: true before attaching another");
        AttachingConnector c=Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(x->x.name().equals("com.sun.jdi.ProcessAttach")).findFirst().orElseThrow(()->fail("unsupported","ProcessAttach connector unavailable"));
        Map<String,Connector.Argument> args=c.defaultArguments(); args.get("pid").setValue(Integer.toString(pid));
        VirtualMachine vm;
        try { vm=c.attach(args); }
        catch (IOException e) { throw fail("attach_unavailable","Attach requires a permitted, JDWP-enabled JVM with PID "+pid+": "+e.getMessage()); }
        Session s=new Session(UUID.randomUUID().toString(),vm,cwd.toAbsolutePath().normalize(),false,null,pid);
        s.target="PID "+pid;
        sessions.put(s.id,s);
        event(s,"attach",Json.MAPPER.createObjectNode().put("pid",pid).put("consent",true));
        initialize(s,false);
        return status(s,"Attached to explicitly selected PID "+pid+"; detaching will not terminate it");
    }
    private static void initialize(Session s,boolean stopOnEntry) {
        synchronized(s) {
            ClassPrepareRequest prepare=s.vm.eventRequestManager().createClassPrepareRequest();
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL); prepare.enable();
            if(stopOnEntry) {
                // VMStart is generated for JDI launching connectors before any application instruction executes.
                s.reason="entry";
            }
        }
        Thread loop=Thread.ofVirtual().name("debug-events-"+s.id).start(()->eventsLoop(s,stopOnEntry));
        if(s.process!=null) {
            s.outputReaders=2;
            Thread.ofVirtual().name("debug-stdout-"+s.id).start(()->drain(s,s.process.getInputStream(),"stdout"));
            Thread.ofVirtual().name("debug-stderr-"+s.id).start(()->drain(s,s.process.getErrorStream(),"stderr"));
        }
        if(s.process!=null) Thread.ofVirtual().name("debug-exit-"+s.id).start(()->{
            try { int exit=s.process.waitFor(); synchronized(s) { s.exitCode=exit; if(!s.closed && !s.state.equals("completed")) complete(s,"Target exited with code "+exit); } }
            catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
    }
    private static void drain(Session s,InputStream in,String stream) {
        try(InputStreamReader reader=new InputStreamReader(in,StandardCharsets.UTF_8)) {
            char[] chars=new char[1024]; StringBuilder pending=new StringBuilder(); int read;
            while((read=reader.read(chars))!=-1) {
                for(int i=0;i<read;i++) {
                    pending.append(chars[i]);
                    if(chars[i]=='\n') { outputChunk(s,stream,pending.toString()); pending.setLength(0); }
                    else if(pending.length()>=4096) {
                        int end=safeOutputBoundary(s,pending);
                        outputChunk(s,stream,pending.substring(0,end)); pending.delete(0,end);
                    }
                }
            }
            if(!pending.isEmpty()) outputChunk(s,stream,pending.toString());
        } catch(IOException ignored) { }
        finally { synchronized(s) { s.outputReaders--; s.notifyAll(); } }
    }
    private static int safeOutputBoundary(Session s,StringBuilder pending) {
        int overlap=Math.max(s.knownSecrets.stream().mapToInt(String::length).max().orElse(1)-1,
            s.extraSecrets.stream().mapToInt(String::length).max().orElse(1)-1);
        int end=pending.length()-Math.min(4095,overlap);
        for(String secret:s.knownSecrets) end=avoidSplitSecret(pending,secret,end);
        for(String secret:s.extraSecrets) end=avoidSplitSecret(pending,secret,end);
        return Math.max(1,end);
    }
    private static int avoidSplitSecret(StringBuilder pending,String secret,int boundary) {
        int from=0, at;
        while((at=pending.indexOf(secret,from))>=0 && at<boundary) {
            if(at+secret.length()>boundary) boundary=at;
            from=at+1;
        }
        return boundary;
    }
    private static void outputChunk(Session s,String stream,String text) {
        synchronized(s) {
            if(!s.captureOutput) return;
            for(String secret:s.knownSecrets) text=text.replace(secret,"[REDACTED]");
            for(String secret:s.extraSecrets) text=text.replace(secret,"[REDACTED]");
            ObjectNode o=Json.MAPPER.createObjectNode().put("stream",stream).put("text",text)
                .put("timestamp",Instant.now().toString()).put("cursor",++s.outputCursor);
            s.output.addLast(o); if(s.output.size()>MAX_OUTPUT) s.output.removeFirst(); s.notifyAll();
        }
    }
    private static void eventsLoop(Session s,boolean stopOnEntry) {
        try {
            while(!s.closed) {
                EventSet set=s.vm.eventQueue().remove(300);
                if(set==null) continue;
                boolean keep=false;
                synchronized(s) {
                    for(Event e:set) {
                        if(e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) { complete(s,"Target exited or debugger disconnected"); break; }
                        if(e instanceof ClassPrepareEvent cp) {
                            for(Breakpoint bp:s.breakpoints.values()) if(bp.enabled) resolve(s,bp,cp.referenceType());
                        }
                        if(e instanceof VMStartEvent start && stopOnEntry) {
                            stop(s,set,start.thread(),"entry",null,null,null,null); keep=true; break;
                        }
                        if(e instanceof LocatableEvent loc && !(e instanceof ClassPrepareEvent)) {
                            Breakpoint bp=null;
                            if(e.request()!=null) for(Breakpoint candidate:s.breakpoints.values())
                                if(candidate.requests.contains(e.request())) { bp=candidate; break; }
                            if(bp==null && !(e instanceof StepEvent && e.request()==s.stepRequest)) continue;
                            if(bp!=null && (!bp.enabled || bp.thread!=null && !bp.thread.equals(Long.toString(loc.thread().uniqueID())))) continue;
                            if(e instanceof ExceptionEvent exception && bp!=null) {
                                if(bp.exception!=null && !bp.exception.equals(exception.exception().referenceType().name())) continue;
                                if(bp.className!=null && !bp.className.equals(loc.location().declaringType().name())) continue;
                                if(bp.line>0 && bp.line!=loc.location().lineNumber()) continue;
                                if(bp.path!=null && !matchesPath(s,bp.path,loc.location())) continue;
                            }
                            if(bp!=null && bp.type.startsWith("method") &&
                                (!loc.location().method().name().equals(bp.method) || bp.signature!=null && !loc.location().method().signature().equals(bp.signature))) continue;
                            if(bp!=null) {
                                bp.hits++;
                                if(bp.hitCount>0 && (bp.hitPolicy.equals("exact") && bp.hits!=bp.hitCount
                                        || bp.hitPolicy.equals("after") && bp.hits<bp.hitCount
                                        || bp.hitPolicy.equals("every") && bp.hits%bp.hitCount!=0)) continue;
                                if(bp.condition!=null) {
                                    try { if(!truth(evaluateValue(loc.thread().frame(0),bp.condition))) continue; }
                                    catch(Exception ex) { event(s,"breakpoint_error",Json.MAPPER.createObjectNode().put("breakpoint_id",bp.id).put("message",ex.getMessage())); continue; }
                                }
                                if(bp.log!=null) {
                                    ObjectNode data=Json.MAPPER.createObjectNode().put("breakpoint_id",bp.id);
                                    try {
                                        ObjectNode preview=value(s,evaluateValue(loc.thread().frame(0),bp.log));
                                        preview.remove("reference"); data.set("value",preview);
                                    } catch(Exception ex) { data.put("error",ex.getMessage()); }
                                    event(s,"logpoint",data);
                                    if(bp.oneShot) remove(s,bp);
                                    continue;
                                }
                            }
                            String why=e instanceof ExceptionEvent?"exception":e instanceof StepEvent?"step":e instanceof WatchpointEvent?"field":
                                e instanceof MethodEntryEvent?"method_entry":e instanceof MethodExitEvent?"method_exit":"breakpoint";
                            stop(s,set,loc.thread(),why,bp,e instanceof ExceptionEvent ex?ex:null,
                                e instanceof WatchpointEvent watch?watch:null,
                                e instanceof MethodExitEvent exit?exit:null); keep=true;
                            if(bp!=null && bp.oneShot) { remove(s,bp); }
                            break;
                        }
                    }
                }
                if(!keep) try { set.resume(); } catch(VMDisconnectedException ignored) { }
            }
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch(VMDisconnectedException ignored) { synchronized(s) { complete(s,"Target disconnected"); } }
        catch(Exception ex) { synchronized(s) { s.failure=ex.toString(); complete(s,"Debugger event loop failed: "+ex); } }
    }
    private static void event(Session s,String type,ObjectNode data) {
        synchronized(s) {
            ObjectNode e=Json.MAPPER.createObjectNode().put("cursor",++s.eventCursor).put("type",type).put("timestamp",Instant.now().toString());
            e.set("data",data); s.events.addLast(e); if(s.events.size()>MAX_EVENTS) s.events.removeFirst(); s.notifyAll();
        }
    }
    private static void complete(Session s,String message) {
        if(s.closed || s.state.equals("completed")) return;
        s.state="completed"; s.completionReason=message;
        s.stopId=null; s.references.clear(); s.suspendedSet=null;
        ObjectNode data=Json.MAPPER.createObjectNode().put("message",message);
        if(s.exitCode!=Integer.MIN_VALUE) data.put("exit_code",s.exitCode);
        event(s,"exit",data);
        s.notifyAll();
    }
    private static void stop(Session s,EventSet set,ThreadReference thread,String why,Breakpoint bp,ExceptionEvent exception,
                             WatchpointEvent watchpoint,MethodExitEvent methodExit) {
        s.stopId=s.id+":"+(++s.stopSequence); s.state="stopped"; s.stoppedThread=thread; s.suspendedSet=set;
        s.manuallySuspended=set==null; s.references.clear(); s.reason=why; s.breakpointId=bp==null?null:bp.id; s.exceptionEvent=exception;
        s.watchpointEvent=watchpoint; s.methodExitEvent=methodExit;
        event(s,"stop",stopData(s)); s.notifyAll();
    }
    private static ObjectNode stopData(Session s) {
        ObjectNode data=Json.MAPPER.createObjectNode().put("stop_id",s.stopId).put("reason",s.reason);
        if(s.breakpointId!=null) data.put("breakpoint_id",s.breakpointId);
        if(s.stoppedThread!=null) {
            data.put("thread_id",Long.toString(s.stoppedThread.uniqueID()));
            try {
                Location loc=s.stoppedThread.frame(0).location(); data.set("location",location(s,loc));
                try {
                    int line=loc.lineNumber();
                    if(line>0) for(String root:List.of("src/main/java","src/test/java")) {
                        Path file=path(s.cwd,root).resolve(loc.sourcePath()).normalize();
                        if(Files.isRegularFile(file) && Files.size(file)<=256_000) {
                            ArrayNode excerpt=data.putArray("source_excerpt");
                            try(var lines=Files.lines(file)) {
                                java.util.concurrent.atomic.AtomicInteger counter=new java.util.concurrent.atomic.AtomicInteger(Math.max(1,line-2));
                                lines.skip(Math.max(0,line-3)).limit(5).forEach(text ->
                                    excerpt.addObject().put("line",counter.getAndIncrement()).put("text",text.substring(0,Math.min(240,text.length()))));
                            }
                            break;
                        }
                    }
                } catch(Exception ignored) { data.put("source_excerpt_unavailable",true); }
            } catch(Exception ignored) { data.put("location_unavailable",true); }
        }
        if(s.watchpointEvent!=null) try {
            data.put("field",s.watchpointEvent.field().name());
            data.set("old_value",value(s,s.watchpointEvent.valueCurrent()));
            if(s.watchpointEvent instanceof ModificationWatchpointEvent change)
                data.set("new_value",value(s,change.valueToBe()));
        } catch(Exception ex) { data.put("field_values_unavailable",true); }
        if(s.methodExitEvent!=null && s.vm.canGetMethodReturnValues()) try {
            data.set("return_value",value(s,s.methodExitEvent.returnValue()));
        } catch(Exception ex) { data.put("return_value_unavailable",true); }
        data.put("suspension_policy",s.suspendedSet==null || s.suspendedSet.suspendPolicy()==EventRequest.SUSPEND_ALL?"all_threads":"event_thread");
        data.put("other_threads_running",s.suspendedSet!=null && s.suspendedSet.suspendPolicy()==EventRequest.SUSPEND_EVENT_THREAD);
        return data;
    }
    private static ObjectNode location(Session s,Location loc) {
        ObjectNode o=Json.MAPPER.createObjectNode().put("class_name",loc.declaringType().name())
            .put("method_name",loc.method().name()).put("signature",loc.method().signature()).put("code_index",loc.codeIndex());
        int line=loc.lineNumber(); if(line>=1) o.put("line",line);
        try { o.put("source_path",loc.sourcePath()); } catch(AbsentInformationException ex) { o.put("source_unavailable",true); }
        try { if(loc.declaringType().classLoader()!=null) o.put("class_loader_id",loc.declaringType().classLoader().uniqueID()); }
        catch(Exception ignored) { }
        try { if(loc.declaringType().module()!=null) o.put("module",loc.declaringType().module().name()); }
        catch(Exception ignored) { }
        return o;
    }
    private ObjectNode sessionList(ObjectNode a) {
        ObjectNode out=node("ok","Owned debugger sessions"); ArrayNode list=out.putArray("sessions");
        List<Session> all=sessions.values().stream().sorted(Comparator.comparing(s->s.id)).toList();
        int start=cursor(a), end=Math.min(all.size(),start+limit(a));
        for(int i=Math.min(start,all.size());i<end;i++) list.add(status(all.get(i),"Session status"));
        out.put("truncated",end<all.size()); if(end<all.size()) out.put("next_cursor",end);
        return out;
    }
    /** Dynamic state only; execution tools do not repeat launch configuration or capabilities. */
    private static ObjectNode executionState(Session s,String summary) {
        synchronized(s) {
            ObjectNode out=node(s.state,summary).put("session_id",s.id).put("pid",s.pid)
                .put("output_cursor",s.outputCursor).put("output_complete",s.outputReaders==0)
                .put("event_cursor",s.eventCursor);
            if(s.stopId!=null) out.set("stop",stopData(s));
            if(s.exitCode!=Integer.MIN_VALUE) out.put("exit_code",s.exitCode)
                .put("exit_code_source",s.runnerPid>0?"maven_runner":"target");
            if(s.failure!=null) out.put("failure",s.failure);
            if(s.completionReason!=null) out.put("completion_reason",s.completionReason);
            if(s.outputWarning!=null) out.put("output_warning",s.outputWarning);
            return out;
        }
    }
    private static ObjectNode status(Session s,String summary) {
        synchronized(s) {
            ObjectNode out=executionState(s,summary).put("target",s.target)
                .put("ownership",s.launched?"launched":"attached").put("breakpoint_count",s.breakpoints.size());
            ArrayNode configured=out.putArray("breakpoints");
            s.breakpoints.values().stream().limit(10).forEach(bp -> configured.addObject()
                .put("breakpoint_id",bp.id).put("type",bp.type).put("enabled",bp.enabled)
                .put("verification",bp.pending==null?"verified":"pending"));
            out.put("breakpoints_truncated",s.breakpoints.size()>10);
            if(s.breakpoints.size()>10) out.put("breakpoints_next_cursor",10);
            ArrayNode args=out.putArray("arguments"); appendRedactedArguments(args,s.targetArguments);
            ArrayNode opts=out.putArray("jvm_options"); appendRedactedArguments(opts,s.jvmOptions);
            ArrayNode env=out.putArray("environment_overrides"); s.environmentNames.forEach(env::add);
            if(s.runnerPid>0) out.put("runner_pid",s.runnerPid);
            ObjectNode caps=out.putObject("capabilities"); caps.put("jdi",true).put("virtual_threads",true)
                .put("virtual_thread_carriers",false).put("mutation",false).put("safe_evaluation",true)
                .put("source_bytecode_validation",false).put("attached_output_capture",false);
            try { caps.put("field_access",s.vm.canWatchFieldAccess()).put("field_modification",s.vm.canWatchFieldModification())
                .put("owned_monitor_info",s.vm.canGetOwnedMonitorInfo()); }
            catch(VMDisconnectedException ex) { caps.put("target_connected",false); }
            return out;
        }
    }
    private static void appendRedactedArguments(ArrayNode output,List<String> values) {
        boolean redactNext=false;
        for(String argument:values) {
            if(redactNext) { output.add("[REDACTED]"); redactNext=false; continue; }
            if(sensitive(argument)) {
                int equals=argument.indexOf('=');
                if(equals>=0) output.add(argument.substring(0,Math.min(equals+1,256))+"[REDACTED]");
                else { output.add(argument.substring(0,Math.min(argument.length(),256))); redactNext=true; }
            } else output.add(argument.substring(0,Math.min(argument.length(),256)));
        }
    }
    private Session session(JsonNode a) { String id=str(a,"session_id",null); required(id,"session_id");
        Session s=sessions.get(id); if(s==null) throw fail("session_not_found","No debugger session "+id+"; call debug_sessions"); return s; }
    private static void checkStop(Session s,JsonNode a) {
        String id=str(a,"stop_id",null); required(id,"stop_id");
        if(!id.equals(s.stopId) || !s.state.equals("stopped")) throw fail("stale_stop","Stop "+id+" is no longer active; call debug_status or debug_wait");
    }
    private ObjectNode dispatch(String op,ObjectNode a,java.util.function.BooleanSupplier cancelled) throws Exception {
        Session s=session(a);
        synchronized(s) {
            ObjectNode result=switch(op) {
                case "debug_status" -> status(s,"Current target state");
                case "debug_detach" -> {
                    ObjectNode detachedResult=detach(s,a);
                    if(bool(a,"close_session",false)) {
                        s.captureOutput=false; s.output.clear(); s.events.clear();
                        s.knownSecrets=List.of(); s.extraSecrets=List.of();
                        sessions.remove(s.id);
                    }
                    yield detachedResult;
                }
                case "debug_breakpoints" -> breakpoints(s,a);
                case "debug_continue" -> resume(s,a,false,cancelled);
                case "debug_step" -> resume(s,a,true,cancelled);
                case "debug_run_to" -> runTo(s,a,cancelled);
                case "debug_pause" -> pause(s,a);
                case "debug_wait" -> waitEvent(s,a,cancelled);
                case "debug_events" -> pageQueue(s.events,s.eventCursor,a,"events");
                case "debug_output" -> {
                    if(!s.launched) throw fail("unsupported","JDI cannot capture stdout/stderr from an attached JVM; use the target's own logs");
                    if(!s.captureOutput) throw fail("unsupported",s.outputWarning==null
                        ? "Output capture was disabled at launch; relaunch with capture_output: true"
                        : s.outputWarning);
                    ObjectNode outputResult=pageQueue(s.output,s.outputCursor,a,"output").put("output_complete",s.outputReaders==0);
                    if(s.outputWarning!=null) outputResult.put("output_warning",s.outputWarning);
                    yield outputResult;
                }
                case "debug_threads" -> threads(s,a);
                case "debug_stack" -> stack(s,a);
                case "debug_variables" -> variables(s,a);
                case "debug_object" -> object(s,a);
                case "debug_source" -> source(s,a);
                case "debug_exception" -> exception(s,a);
                case "debug_evaluate" -> evaluate(s,a,cancelled);
                default -> throw fail("unsupported","Unknown debugger operation: "+op);
            };
            if(!result.has("session_id")) result.put("session_id",s.id);
            if(!result.has("pid")) result.put("pid",s.pid);
            return result;
        }
    }
    private static ObjectNode pageQueue(ArrayDeque<ObjectNode> queue,long current,ObjectNode a,String field) {
        long since=cursor(a), oldest=queue.isEmpty()?current+1:queue.getFirst().path("cursor").asLong();
        ObjectNode out=node("ok","Ordered "+field+" since cursor "+since).put("cursor",current).put("gap",since<oldest-1);
        ArrayNode arr=out.putArray(field); int count=0, bytes=0, lines=0; long last=since;
        String stream=str(a,"stream",null), thread=str(a,"thread_id",null);
        if(field.equals("output") && stream!=null && !List.of("stdout","stderr").contains(stream))
            throw fail("invalid_argument","stream must be stdout or stderr");
        List<String> eventTypes=strings(a,"event_types");
        if(field.equals("events")) validateEventTypes(eventTypes);
        int byteLimit=field.equals("output")?num(a,"byte_limit",51200,51200):0;
        int lineLimit=field.equals("output")?num(a,"line_limit",2000,2000):0;
        if(field.equals("output") && (byteLimit==0 || lineLimit==0)) throw fail("invalid_argument","Output byte_limit and line_limit must be positive");
        for(ObjectNode item:queue) if(item.path("cursor").asLong()>since) {
            long sequence=item.path("cursor").asLong();
            if(field.equals("output") && stream!=null && !stream.equals(item.path("stream").asText())
                || field.equals("events") && !eventTypes.isEmpty() && !eventTypes.contains(item.path("type").asText())
                || field.equals("events") && thread!=null && !thread.equals(item.path("data").path("thread_id").asText())) {
                last=sequence; continue;
            }
            if(count>=limit(a)) break;
            if(field.equals("output")) {
                String text=item.path("text").asText();
                boolean suppress=bool(a,"suppress_output",false);
                int encoded=suppress?"[suppressed]".length():text.getBytes(StandardCharsets.UTF_8).length;
                int itemLines=(int)text.chars().filter(ch->ch=='\n').count();
                if(itemLines==0 && !text.isEmpty()) itemLines=1;
                if(bytes+encoded>byteLimit || lines+itemLines>lineLimit) {
                    if(count==0) throw fail("limit_too_small","Increase byte_limit and line_limit to include the next output segment ("+encoded+" bytes, "+itemLines+" lines), or use suppress_output");
                    break;
                }
                bytes+=encoded; lines+=itemLines;
                if(suppress) arr.add(item.deepCopy().put("text","[suppressed]"));
                else arr.add(item.deepCopy());
            } else arr.add(item.deepCopy());
            last=sequence; count++;
        }
        if(field.equals("output")) out.put("sensitive_data_warning","Target output may contain secrets; known sensitive environment values are redacted");
        out.put("truncated",last<current); if(last<current) out.put("next_cursor",last);
        return out;
    }
    private ObjectNode waitEvent(Session s,ObjectNode a,java.util.function.BooleanSupplier cancelled) throws InterruptedException {
        long since=cursor(a), end=System.nanoTime()+num(a,"wait_ms",1000,30000)*1_000_000L;
        List<String> types=strings(a,"event_types"); validateEventTypes(types);
        String thread=str(a,"thread_id",null);
        while(s.state.equals("running") && !eventGap(s,since) && !matchingEvent(s,since,types,thread)) {
            if(cancelled.getAsBoolean()) throw fail("cancelled","Wait cancelled; target execution was not changed");
            long remaining=end-System.nanoTime(); if(remaining<=0) break;
            long slice=Math.min(remaining,100_000_000L);
            s.wait(slice/1_000_000L,(int)(slice%1_000_000L));
        }
        boolean available=matchingEvent(s,since,types,thread), gap=eventGap(s,since);
        boolean expired=!available && !gap && s.state.equals("running");
        String summary=gap?"Event history gap; inspect debug_events":available?"New matching event available":
            expired?"Wait expired; target is still running":"Target state unchanged; no new matching event";
        ObjectNode out=executionState(s,summary).put("event_available",available).put("wait_expired",expired);
        if(gap) out.put("gap",true);
        out.put("next_event_cursor",s.eventCursor); return out;
    }
    private static void validateEventTypes(List<String> types) {
        for(String type:types) if(!EVENT_TYPES.contains(type))
            throw fail("invalid_argument","Unknown event type "+type+"; choices: "+EVENT_TYPES);
    }
    private static boolean eventGap(Session s,long since) {
        return !s.events.isEmpty() && since<s.events.getFirst().path("cursor").asLong()-1;
    }
    private static boolean matchingEvent(Session s,long since,List<String> types,String thread) {
        if(s.eventCursor<=since) return false;
        for(ObjectNode event:s.events) if(event.path("cursor").asLong()>since
            && (types.isEmpty() || types.contains(event.path("type").asText()))
            && (thread==null || thread.equals(event.path("data").path("thread_id").asText()))) return true;
        return false;
    }
    private static ObjectNode detach(Session s,ObjectNode a) {
        boolean terminate=bool(a,"terminate",false);
        if(terminate && !s.launched) throw fail("permission_required","An attached JVM cannot be terminated by debug_detach");
        if(terminate==bool(a,"leave_running",true)) throw fail("invalid_argument","Choose an explicit disposition: leave_running: true, or terminate: true with leave_running: false");
        event(s,"detach",Json.MAPPER.createObjectNode().put("pid",s.pid).put("terminate",terminate));
        s.closed=true; s.stopId=null; s.references.clear();
        if(terminate && s.process!=null) {
            List<ProcessHandle> descendants=s.process.descendants().toList();
            for(ProcessHandle child:descendants.reversed()) child.destroyForcibly();
            s.process.destroyForcibly();
            try { s.process.waitFor(1,java.util.concurrent.TimeUnit.SECONDS); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        try { s.vm.dispose(); } catch(VMDisconnectedException ignored) {}
        s.state="completed"; s.completionReason=terminate?"Detached and terminated launched target":"Detached; target left running";
        s.notifyAll();
        boolean terminated=terminate && (s.process==null || !s.process.isAlive());
        if(terminate && !terminated) throw fail("termination_failed","Launched target is still alive; retry explicit termination for PID "+s.pid);
        return node("completed",terminate?"Detached and terminated launched target":"Detached; target left running")
            .put("session_id",s.id).put("pid",s.pid).put("terminated",terminated);
    }
    private ObjectNode pause(Session s,ObjectNode a) throws Exception {
        num(a,"wait_ms",1000,30000);
        if(s.state.equals("stopped")) return executionState(s,"Already stopped");
        if(!s.state.equals("running")) throw fail("invalid_state","Target is not running");
        ThreadReference selected=selectThread(s,str(a,"thread_id",null),false); s.vm.suspend();
        stop(s,null,selected,"pause",null,null,null,null);
        return executionState(s,"Paused all threads; no automatic resume");
    }
    private static ThreadReference selectThread(Session s,String id,boolean stoppedOnly) {
        if(id==null && s.state.equals("stopped") && s.stoppedThread!=null && s.stoppedThread.isSuspended()) return s.stoppedThread;
        for(ThreadReference t:s.vm.allThreads()) if(id==null || Long.toString(t.uniqueID()).equals(id)) {
            if(!stoppedOnly || t.isSuspended()) return t;
        }
        throw fail("thread_not_found","No matching "+(stoppedOnly?"suspended ":"")+"thread "+id);
    }
    private ObjectNode resume(Session s,ObjectNode a,boolean stepping,java.util.function.BooleanSupplier cancelled) throws InterruptedException {
        int waitMs=num(a,"wait_ms",1000,30000); // Reject invalid waits before creating requests or resuming.
        checkStop(s,a);
        if(stepping) {
            ThreadReference t=selectThread(s,str(a,"thread_id",null),true);
            String dir=str(a,"direction", "over"); int depth=switch(dir) {
                case "into" -> StepRequest.STEP_INTO; case "over" -> StepRequest.STEP_OVER;
                case "out" -> StepRequest.STEP_OUT; default -> throw fail("invalid_argument","direction must be into, over, or out"); };
            if(s.stepRequest!=null) { s.vm.eventRequestManager().deleteEventRequest(s.stepRequest); s.stepRequest=null; }
            StepRequest request=s.vm.eventRequestManager().createStepRequest(t,StepRequest.STEP_LINE,depth);
            request.addCountFilter(1); for(String skip:strings(a,"skip_filters")) request.addClassExclusionFilter(skip);
            request.setSuspendPolicy(EventRequest.SUSPEND_ALL); request.enable(); s.stepRequest=request;
        }
        s.stopId=null; s.references.clear(); s.exceptionEvent=null; s.watchpointEvent=null; s.methodExitEvent=null; s.state="running";
        event(s,"resume",Json.MAPPER.createObjectNode().put("thread_id",s.stoppedThread==null?"":Long.toString(s.stoppedThread.uniqueID()))
            .put("scope",s.manuallySuspended || s.suspendedSet==null || s.suspendedSet.suspendPolicy()==EventRequest.SUSPEND_ALL?"all_threads":"event_thread"));
        if(s.manuallySuspended) s.vm.resume(); else if(s.suspendedSet!=null) s.suspendedSet.resume();
        s.suspendedSet=null; s.manuallySuspended=false;
        return awaitExecution(s,waitMs,cancelled);
    }
    private static ObjectNode awaitExecution(Session s,int waitMs,java.util.function.BooleanSupplier cancelled) throws InterruptedException {
        long end=System.nanoTime()+waitMs*1_000_000L;
        while(s.state.equals("running")) {
            if(cancelled.getAsBoolean()) throw fail("cancelled","Execution wait cancelled; target was resumed and was not paused or terminated; inspect debug_status");
            long remaining=end-System.nanoTime();
            if(remaining<=0) break;
            // Release the session monitor so the event loop can publish the next stop/exit.
            long slice=Math.min(remaining,100_000_000L);
            s.wait(slice/1_000_000L,(int)(slice%1_000_000L));
        }
        boolean expired=s.state.equals("running");
        return executionState(s,expired?"Wait expired; target is still running":s.state.equals("stopped")?"Target stopped":"Target completed")
            .put("wait_expired",expired);
    }
    private ObjectNode runTo(Session s,ObjectNode a,java.util.function.BooleanSupplier cancelled) throws InterruptedException {
        num(a,"wait_ms",1000,30000); // Do not install a one-shot breakpoint for an invalid wait.
        checkStop(s,a);
        ObjectNode bp=a.deepCopy(); bp.put("type","source").put("one_shot",true);
        Breakpoint spec=parseBreakpoint(bp); List<Location> found=findLocations(s,s.vm.allClasses(),spec);
        if(found.size()!=1) throw fail("ambiguous_location","Run-to requires exactly one loaded executable location; found "+found.size()
            +". Choices: "+found.stream().limit(10).map(loc->location(s,loc).toString()).toList());
        BreakpointRequest request=s.vm.eventRequestManager().createBreakpointRequest(found.getFirst());
        if(spec.thread!=null) request.addThreadFilter(selectThread(s,spec.thread,true));
        request.setSuspendPolicy(EventRequest.SUSPEND_ALL); request.enable();
        spec.id="run-to-"+UUID.randomUUID(); spec.requests.add(request); spec.locations.add(found.getFirst()); spec.pending=null;
        s.breakpoints.put(spec.id,spec); return resume(s,a,false,cancelled);
    }
    private static Breakpoint parseBreakpoint(JsonNode a) {
        Breakpoint bp=new Breakpoint();
        bp.id=str(a,"breakpoint_id",UUID.randomUUID().toString()); bp.type=str(a,"type","source");
        bp.className=str(a,"class_name",null); bp.method=str(a,"method_name",null);
        bp.signature=str(a,"signature",null); bp.path=str(a,"source_path",null);
        bp.line=num(a,"line",0,Integer.MAX_VALUE); bp.field=str(a,"field_name",null);
        bp.exception=str(a,"exception_class",null); bp.condition=str(a,"condition",null);
        bp.log=str(a,"log_expression",null); bp.thread=str(a,"thread_id",null);
        bp.policy=str(a,"suspension_policy","all_threads");
        if(!bp.policy.equals("all_threads") && !bp.policy.equals("event_thread")) throw fail("invalid_argument","suspension_policy must be all_threads or event_thread");
        bp.enabled=bool(a,"enabled",true); bp.oneShot=bool(a,"one_shot",false);
        bp.caught=bool(a,"caught",true); bp.uncaught=bool(a,"uncaught",true);
        bp.access=bool(a,"access",false); bp.modification=bool(a,"modification",true);
        bp.hitCount=num(a,"hit_count",0,Integer.MAX_VALUE);
        bp.hitPolicy=str(a,"hit_policy","after");
        if(!List.of("after","exact","every").contains(bp.hitPolicy))
            throw fail("invalid_argument","hit_policy must be after, exact, or every");
        if(bp.condition!=null) validateExpression(bp.condition);
        if(bp.log!=null) validateExpression(bp.log);
        switch(bp.type) {
            case "source" -> { required(bp.path,"source_path"); if(bp.line<1) throw fail("invalid_argument","Source breakpoint requires a 1-based line"); }
            case "method_entry", "method_exit" -> { required(bp.className,"class_name"); required(bp.method,"method_name"); }
            case "exception" -> { if(!bp.caught&&!bp.uncaught) throw fail("invalid_argument","Enable caught or uncaught exceptions"); }
            case "field" -> { required(bp.className,"class_name"); required(bp.field,"field_name"); }
            default -> throw fail("unsupported","Breakpoint type "+bp.type+" is unsupported");
        }
        if(bp.type.equals("source")) bp.pending=unloadedSourceReason(bp);
        return bp;
    }
    private static String unloadedSourceReason(Breakpoint bp) {
        return bp.className==null
            ? "No matching source class is loaded yet for "+bp.path+"; waiting for class preparation"
            : "Class "+bp.className+" is not loaded yet; waiting for class preparation";
    }
    private static ObjectNode breakpointData(Session s,Breakpoint bp) {
        ObjectNode o=Json.MAPPER.createObjectNode().put("breakpoint_id",bp.id).put("type",bp.type).put("enabled",bp.enabled)
            .put("hits",bp.hits).put("verification",bp.pending==null?"verified":"pending");
        if(bp.pending!=null) o.put("pending_reason",bp.pending);
        ArrayNode locations=o.putArray("locations"); for(Location loc:bp.locations) locations.add(location(s,loc));
        return o;
    }
    private ObjectNode breakpoints(Session s,ObjectNode a) {
        String action=str(a,"action","list");
        if(action.equals("list")) {
            ObjectNode out=node("ok","Breakpoints for session "+s.id); ArrayNode array=out.putArray("breakpoints");
            List<Breakpoint> all=new ArrayList<>(s.breakpoints.values()); int begin=Math.min(cursor(a),all.size()); int end=Math.min(all.size(),begin+limit(a));
            for(int i=begin;i<end;i++) array.add(breakpointData(s,all.get(i)));
            out.put("truncated",end<all.size()); if(end<all.size()) out.put("next_cursor",end); return out;
        }
        if(!List.of("add","remove","enable","disable","update").contains(action)) throw fail("invalid_argument","Unknown breakpoint action: "+action);
        List<JsonNode> specs=new ArrayList<>();
        if(a.path("breakpoints").isArray() && !a.path("breakpoints").isEmpty()) a.path("breakpoints").forEach(specs::add);
        else if(a.path("breakpoint_ids").isArray() && !a.path("breakpoint_ids").isEmpty())
            for(JsonNode id:a.path("breakpoint_ids")) specs.add(Json.MAPPER.createObjectNode().put("breakpoint_id",id.asText()));
        else specs.add(a);
        if(specs.size()>64 || action.equals("add") && s.breakpoints.size()+specs.size()>256)
            throw fail("limit","Too many breakpoints in one request or session (maximum 64 per request, 256 per session)");
        List<Breakpoint> parsed=new ArrayList<>();
        // Validate every specification before making changes, including IDs and ambiguous loaded locations.
        for(JsonNode spec:specs) {
            if(action.equals("add")) {
                Breakpoint bp=parseBreakpoint(spec);
                if(s.breakpoints.containsKey(bp.id) || parsed.stream().anyMatch(x->x.id.equals(bp.id))) throw fail("duplicate_breakpoint","Duplicate breakpoint ID "+bp.id);
                if(bp.type.equals("field") && (bp.access&&!s.vm.canWatchFieldAccess() || bp.modification&&!s.vm.canWatchFieldModification()))
                    throw fail("unsupported","Target does not support the requested field watchpoint");
                if(bp.className!=null && s.vm.classesByName(bp.className).size()>1)
                    throw fail("ambiguous_class","Several loaded class loaders define "+bp.className+": "
                        +s.vm.classesByName(bp.className).stream().limit(10)
                            .map(type->type.classLoader()==null?"bootstrap":Long.toString(type.classLoader().uniqueID())).toList()
                        +"; class-loader selection is not supported");
                if(bp.type.startsWith("method") && s.vm.classesByName(bp.className).stream()
                    .anyMatch(t->t.methodsByName(bp.method).size()>1 && bp.signature==null))
                    throw fail("ambiguous_method","Overloaded method "+bp.method+" requires signature. Choices: "
                        +s.vm.classesByName(bp.className).stream().flatMap(t->t.methodsByName(bp.method).stream())
                            .map(Method::signature).distinct().limit(10).toList());
                if(bp.type.equals("source") && findLocations(s,s.vm.allClasses(),bp).size()>1)
                    throw fail("ambiguous_location","Source path/line resolves to multiple locations; include class_name and method_name. Choices: "
                        +findLocations(s,s.vm.allClasses(),bp).stream().limit(10).map(loc->location(s,loc).toString()).toList());
            } else {
                String id=str(spec,"breakpoint_id",null); required(id,"breakpoint_id");
                Breakpoint old=s.breakpoints.get(id); if(old==null) throw fail("breakpoint_not_found","No breakpoint " +id);
                if(action.equals("update")) {
                    ObjectNode merged=Json.MAPPER.createObjectNode();
                    merged.put("breakpoint_id",id).put("type",old.type);
                    if(old.path!=null) merged.put("source_path",old.path);
                    if(old.className!=null) merged.put("class_name",old.className);
                    if(old.method!=null) merged.put("method_name",old.method);
                    if(old.signature!=null) merged.put("signature",old.signature);
                    if(old.field!=null) merged.put("field_name",old.field);
                    merged.put("line",old.line).put("hit_count",old.hitCount).put("hit_policy",old.hitPolicy)
                        .put("caught",old.caught).put("uncaught",old.uncaught)
                        .put("access",old.access).put("modification",old.modification)
                        .put("enabled",old.enabled).put("one_shot",old.oneShot).put("suspension_policy",old.policy);
                    if(old.exception!=null) merged.put("exception_class",old.exception);
                    if(old.condition!=null) merged.put("condition",old.condition);
                    if(old.log!=null) merged.put("log_expression",old.log);
                    if(spec instanceof ObjectNode obj) merged.setAll(obj);
                    Breakpoint replacement=parseBreakpoint(merged);
                    if(replacement.type.equals("source") && findLocations(s,s.vm.allClasses(),replacement).size()>1)
                        throw fail("ambiguous_location","Updated breakpoint resolves to multiple loaded locations");
                }
            }
            parsed.add(action.equals("add")?parseBreakpoint(spec):s.breakpoints.get(str(spec,"breakpoint_id",null)));
        }
        ObjectNode out=node("ok","Breakpoint "+action+" applied"); ArrayNode array=out.putArray("breakpoints");
        for(int i=0;i<specs.size();i++) {
            JsonNode spec=specs.get(i); Breakpoint bp=parsed.get(i);
            if(action.equals("remove")) {
                array.add(breakpointData(s,bp)); remove(s,bp);
                event(s,"breakpoint_change",Json.MAPPER.createObjectNode().put("action",action).put("breakpoint_id",bp.id));
                continue;
            }
            if(action.equals("update")) {
                ObjectNode merged=Json.MAPPER.createObjectNode().put("breakpoint_id",bp.id).put("type",bp.type).put("line",bp.line)
                    .put("caught",bp.caught).put("uncaught",bp.uncaught).put("access",bp.access).put("modification",bp.modification)
                    .put("enabled",bp.enabled).put("one_shot",bp.oneShot).put("hit_count",bp.hitCount)
                    .put("hit_policy",bp.hitPolicy).put("suspension_policy",bp.policy);
                for(String field:List.of("source_path","class_name","method_name","signature","field_name","exception_class","condition","log_expression","thread_id")) {
                    String text=switch(field) { case "source_path"->bp.path; case "class_name"->bp.className; case "method_name"->bp.method;
                        case "signature"->bp.signature; case "field_name"->bp.field; case "exception_class"->bp.exception;
                        case "condition"->bp.condition; case "log_expression"->bp.log; default->bp.thread; };
                    if(text!=null) merged.put(field,text);
                }
                merged.setAll((ObjectNode)spec); remove(s,bp); bp=parseBreakpoint(merged);
            }
            if(action.equals("enable")) bp.enabled=true;
            if(action.equals("disable")) bp.enabled=false;
            if(action.equals("disable")) { clearRequests(s,bp); bp.pending="Disabled"; }
            else if(action.equals("add") || action.equals("update") || action.equals("enable")) {
                s.breakpoints.put(bp.id,bp);
                clearRequests(s,bp);
                if(bp.enabled) {
                    if(bp.type.equals("source")) bp.pending=unloadedSourceReason(bp);
                    if(bp.type.equals("exception")) resolve(s,bp,null);
                    else for(ReferenceType type:s.vm.allClasses()) resolve(s,bp,type);
                } else bp.pending="Disabled";
            }
            array.add(breakpointData(s,bp));
            event(s,"breakpoint_change",Json.MAPPER.createObjectNode().put("action",action).put("breakpoint_id",bp.id));
        }
        return out;
    }
    private static void clearRequests(Session s,Breakpoint bp) {
        for(EventRequest req:bp.requests) try { s.vm.eventRequestManager().deleteEventRequest(req); } catch(Exception ignored) { }
        bp.requests.clear(); bp.locations.clear();
    }
    private static void remove(Session s,Breakpoint bp) { clearRequests(s,bp); s.breakpoints.remove(bp.id); }
    private static List<Location> findLocations(Session s,List<ReferenceType> types,Breakpoint bp) {
        List<Location> found=new ArrayList<>();
        for(ReferenceType type:types) {
            if(bp.className!=null && !type.name().equals(bp.className) || !type.isPrepared()) continue;
            if(bp.type.equals("source")) {
                try {
                    for(Location loc:type.locationsOfLine(bp.line)) {
                        if(bp.method!=null && !bp.method.equals(loc.method().name())) continue;
                        if(bp.signature!=null && !bp.signature.equals(loc.method().signature())) continue;
                        if(matchesPath(s,bp.path,loc)) found.add(loc);
                    }
                } catch(AbsentInformationException ignored) { }
            } else if(bp.type.startsWith("method")) {
                for(Method m:type.methodsByName(bp.method)) if(bp.signature==null || bp.signature.equals(m.signature()))
                    if(m.location()!=null) found.add(m.location());
            }
        }
        return found;
    }
    private static boolean matchesPath(Session s,String requested,Location loc) {
        try { return matchesPath(s,requested,loc.sourcePath()); }
        catch(AbsentInformationException ignored) { return false; }
    }
    private static boolean matchesPath(Session s,String requested,String sourcePath) {
        try {
            Path actual=Path.of(sourcePath); Path want=path(s.cwd,requested);
            if(!want.getFileName().equals(actual.getFileName())) return false;
            // Package-relative JDI paths are resolved against explicit conventional source roots.
            for(String root:List.of("src/main/java","src/test/java"))
                if(path(s.cwd,root).resolve(actual).normalize().equals(want)) return true;
            return !Path.of(requested).isAbsolute() && Path.of(requested).normalize().equals(actual.normalize());
        } catch(IllegalArgumentException ignored) { return false; }
    }
    private static void sourcePending(Session s,Breakpoint bp,ReferenceType type) {
        // Unrelated class preparations must not erase evidence from the matching loaded class.
        if(!bp.requests.isEmpty() || !type.isPrepared() || bp.className!=null && !bp.className.equals(type.name())) return;
        String reason;
        try {
            List<String> paths=type.sourcePaths(null);
            if(paths.stream().noneMatch(source->matchesPath(s,bp.path,source))) {
                if(bp.className==null) return;
                reason="Requested source "+bp.path+" does not match loaded class "+type.name()+" source paths: "+paths;
            } else {
                try {
                    List<Location> lines=type.allLineLocations();
                    if(lines.isEmpty()) {
                        reason="Line-number debug information is unavailable for loaded class "+type.name()+"; compile with line-number debug information";
                    } else if(lines.stream().noneMatch(loc->loc.lineNumber()==bp.line && matchesPath(s,bp.path,loc))) {
                        reason="No executable location at line "+bp.line+" of "+bp.path+" in loaded class "+type.name()+"; choose an executable line (the breakpoint remains pending for further class loads)";
                    } else {
                        reason="Line "+bp.line+" of "+bp.path+" in loaded class "+type.name()+" does not match the requested method/signature";
                    }
                } catch(AbsentInformationException missingLines) {
                    reason="Line-number debug information is unavailable for loaded class "+type.name()+"; compile with line-number debug information";
                }
            }
        } catch(AbsentInformationException missingSource) {
            if(bp.className==null) return;
            reason="Source debug information is unavailable for loaded class "+type.name()+"; compile with source and line-number debug information";
        }
        if(!reason.equals(bp.pending)) {
            bp.pending=reason;
            event(s,"breakpoint_pending",breakpointData(s,bp));
        }
    }
    private static void resolve(Session s,Breakpoint bp,ReferenceType type) {
        if(!bp.enabled) return;
        if(bp.thread!=null) {
            try { selectThread(s,bp.thread,false); }
            catch(Failure ex) {
                bp.pending="Filtered thread no longer exists: "+bp.thread;
                event(s,"breakpoint_pending",breakpointData(s,bp)); return;
            }
        }
        int prior=bp.requests.size();
        if(bp.type.equals("source")) {
            if(!type.isPrepared()) return;
            List<Location> found=findLocations(s,List.of(type),bp);
            if(found.isEmpty()) { sourcePending(s,bp,type); return; }
            if(found.size()>1) { clearRequests(s,bp); bp.pending="Ambiguous executable locations; specify class/method/signature"; return; }
            for(Location loc:found) {
                if(bp.locations.contains(loc)) continue;
                if(!bp.locations.isEmpty()) {
                    clearRequests(s,bp); bp.pending="Multiple loaded classes/locations match; specify a unique class, method, and loader";
                    event(s,"breakpoint_pending",breakpointData(s,bp)); return;
                }
                BreakpointRequest request=s.vm.eventRequestManager().createBreakpointRequest(loc);
                if(bp.thread!=null) request.addThreadFilter(selectThread(s,bp.thread,false));
                request.setSuspendPolicy(bp.policy.equals("event_thread")?EventRequest.SUSPEND_EVENT_THREAD:EventRequest.SUSPEND_ALL);
                request.enable(); bp.requests.add(request); bp.locations.add(loc); bp.pending=null;
            }
        } else if(bp.type.equals("method_entry") || bp.type.equals("method_exit")) {
            if(!type.name().equals(bp.className) || !bp.requests.isEmpty()) return;
            List<Method> methods=type.methodsByName(bp.method).stream()
                .filter(m->bp.signature==null || bp.signature.equals(m.signature())).toList();
            if(methods.size()!=1) {
                bp.pending=methods.isEmpty()?"Method/signature not found in loaded class":"Overloaded method; specify signature";
                event(s,"breakpoint_pending",breakpointData(s,bp)); return;
            }
            MethodEntryRequest entry=null; MethodExitRequest exit=null;
            if(bp.type.equals("method_entry")) { entry=s.vm.eventRequestManager().createMethodEntryRequest(); entry.addClassFilter(type); }
            else { exit=s.vm.eventRequestManager().createMethodExitRequest(); exit.addClassFilter(type); }
            EventRequest request=entry!=null?entry:exit;
            if(bp.thread!=null) {
                ThreadReference t=selectThread(s,bp.thread,false);
                if(entry!=null) entry.addThreadFilter(t); else exit.addThreadFilter(t);
            }
            request.setSuspendPolicy(bp.policy.equals("event_thread")?EventRequest.SUSPEND_EVENT_THREAD:EventRequest.SUSPEND_ALL);
            request.enable(); bp.requests.add(request); bp.pending=null;
        } else if(bp.type.equals("exception")) {
            if(!bp.requests.isEmpty()) return;
            ExceptionRequest request=s.vm.eventRequestManager().createExceptionRequest(null,bp.caught,bp.uncaught);
            if(bp.thread!=null) request.addThreadFilter(selectThread(s,bp.thread,false));
            request.setSuspendPolicy(bp.policy.equals("event_thread")?EventRequest.SUSPEND_EVENT_THREAD:EventRequest.SUSPEND_ALL);
            request.enable(); bp.requests.add(request); bp.pending=null;
        } else if(bp.type.equals("field") && bp.className.equals(type.name())) {
            if(!bp.requests.isEmpty()) {
                clearRequests(s,bp); bp.pending="Multiple loaded class loaders define this field; specify a unique class loader";
                event(s,"breakpoint_pending",breakpointData(s,bp)); return;
            }
            Field field=type.fieldByName(bp.field); if(field==null) { bp.pending="Field not found on loaded class"; return; }
            if(bp.access) {
                AccessWatchpointRequest req=s.vm.eventRequestManager().createAccessWatchpointRequest(field);
                if(bp.thread!=null) req.addThreadFilter(selectThread(s,bp.thread,false));
                req.setSuspendPolicy(bp.policy.equals("event_thread")?EventRequest.SUSPEND_EVENT_THREAD:EventRequest.SUSPEND_ALL);
                req.enable(); bp.requests.add(req);
            }
            if(bp.modification) {
                ModificationWatchpointRequest req=s.vm.eventRequestManager().createModificationWatchpointRequest(field);
                if(bp.thread!=null) req.addThreadFilter(selectThread(s,bp.thread,false));
                req.setSuspendPolicy(bp.policy.equals("event_thread")?EventRequest.SUSPEND_EVENT_THREAD:EventRequest.SUSPEND_ALL);
                req.enable(); bp.requests.add(req);
            }
            bp.pending=null;
        }
        if(bp.pending==null && prior!=bp.requests.size()) event(s,"breakpoint_resolved",breakpointData(s,bp));
    }
    private static ThreadReference stoppedThread(Session s,JsonNode a) {
        checkStop(s,a); return selectThread(s,str(a,"thread_id",null),true);
    }
    private static StackFrame frame(Session s,JsonNode a) {
        ThreadReference t=stoppedThread(s,a);
        String id=str(a,"frame_id",null); int idx=0;
        if(id!=null) {
            String prefix=s.stopId+":"+t.uniqueID()+":";
            if(!id.startsWith(prefix)) throw fail("stale_reference","Frame ID does not belong to this stop/thread");
            try { idx=Integer.parseInt(id.substring(prefix.length())); } catch(NumberFormatException e) { throw fail("invalid_argument","Invalid frame_id"); }
        }
        try { if(idx<0 || idx>=t.frameCount()) throw fail("frame_not_found","No frame "+idx); return t.frame(idx); }
        catch(IncompatibleThreadStateException e) { throw fail("thread_running","Thread is not suspended"); }
    }
    private static ObjectNode threads(Session s,ObjectNode a) {
        checkStop(s,a); List<ThreadReference> all=new ArrayList<>(s.vm.allThreads());
        String filter=str(a,"filter",null), id=str(a,"thread_id",null), state=str(a,"state",null);
        all.removeIf(t->id!=null&&!id.equals(Long.toString(t.uniqueID())) || filter!=null&&!t.name().contains(filter)
            || state!=null&&!threadStatus(t).equals(state));
        all.sort(Comparator.comparingLong(ObjectReference::uniqueID));
        int start=Math.min(cursor(a),all.size()), end=Math.min(all.size(),start+limit(a));
        ObjectNode out=node("ok","Threads at stop "+s.stopId).put("stop_id",s.stopId);
        ArrayNode items=out.putArray("threads");
        for(int i=start;i<end;i++) {
            ThreadReference t=all.get(i); ObjectNode item=items.addObject().put("thread_id",Long.toString(t.uniqueID()))
                .put("name",t.name()).put("virtual",t.isVirtual()).put("state",threadStatus(t)).put("suspended",t.isSuspended());
            if(t.isSuspended()) try { if(t.frameCount()>0) item.set("top_frame",location(s,t.frame(0).location())); }
            catch(IncompatibleThreadStateException ignored) { }
            if(s.vm.canGetCurrentContendedMonitor() && t.isSuspended()) try {
                ObjectReference monitor=t.currentContendedMonitor();
                if(monitor!=null) {
                    item.put("contended_monitor_id",monitor.uniqueID());
                    if(s.vm.canGetMonitorInfo()) {
                        ThreadReference owner=monitor.owningThread();
                        if(owner!=null) item.put("lock_owner_thread_id",Long.toString(owner.uniqueID()));
                    }
                }
            } catch(IncompatibleThreadStateException ignored) { }
            if(s.vm.canGetOwnedMonitorInfo() && t.isSuspended()) try {
                ArrayNode owned=item.putArray("owned_monitors");
                List<ObjectReference> held=t.ownedMonitors();
                for(ObjectReference monitor:held.stream().limit(10).toList()) owned.add(monitor.uniqueID());
                item.put("owned_monitors_truncated",held.size()>10);
            } catch(IncompatibleThreadStateException ignored) { }
        }
        out.put("truncated",end<all.size()); if(end<all.size()) out.put("next_cursor",end); return out;
    }
    private static String threadStatus(ThreadReference t) {
        return switch(t.status()) { case ThreadReference.THREAD_STATUS_RUNNING -> "running";
            case ThreadReference.THREAD_STATUS_WAIT -> "waiting"; case ThreadReference.THREAD_STATUS_MONITOR -> "blocked";
            case ThreadReference.THREAD_STATUS_SLEEPING -> "sleeping"; case ThreadReference.THREAD_STATUS_ZOMBIE -> "terminated";
            default -> "unknown"; };
    }
    private static ObjectNode stack(Session s,ObjectNode a) throws Exception {
        ThreadReference t=stoppedThread(s,a); List<StackFrame> frames=new ArrayList<>(t.frames());
        String filter=str(a,"filter",null); if(filter!=null) frames.removeIf(f->!f.location().declaringType().name().contains(filter));
        if(!bool(a,"include_library_frames",true)) frames.removeIf(f->f.location().declaringType().name().matches("^(java|javax|jdk|sun|com\\.sun)\\..*"));
        int start=Math.min(cursor(a),frames.size()), end=Math.min(frames.size(),start+limit(a));
        ObjectNode out=node("ok","Stack for thread "+t.uniqueID()).put("stop_id",s.stopId).put("thread_id",Long.toString(t.uniqueID()));
        ArrayNode arr=out.putArray("frames");
        for(int i=start;i<end;i++) {
            StackFrame f=frames.get(i); ObjectNode item=location(s,f.location());
            item.put("frame_id",s.stopId+":"+t.uniqueID()+":"+t.frames().indexOf(f));
            if(bool(a,"include_arguments",false)) {
                ArrayNode args=item.putArray("arguments");
                try {
                    for(LocalVariable arg:f.visibleVariables().stream().filter(LocalVariable::isArgument).limit(8).toList()) {
                        ObjectNode data=sensitive(arg.name())
                            ? Json.MAPPER.createObjectNode().put("preview","[REDACTED]").put("availability","redacted")
                            : value(s,f.getValue(arg));
                        data.put("name",arg.name()); args.add(data);
                    }
                } catch(AbsentInformationException noSymbols) { item.put("arguments_unavailable",true); }
            }
            arr.add(item);
        }
        out.put("truncated",end<frames.size()); if(end<frames.size()) out.put("next_cursor",end); return out;
    }
    private static boolean sensitive(String name) {
        return name.matches("(?i).*(?:password|passwd|secret|token|api.?key|credential).*");
    }
    private static ObjectNode value(Session s,Value val) {
        ObjectNode out=Json.MAPPER.createObjectNode();
        if(val==null) return out.put("preview","null").put("availability","null");
        out.put("type",val.type().name()).put("availability","available");
        if(val instanceof StringReference st) {
            String text=st.value(); out.put("preview",text.length()>256?text.substring(0,256):text).put("truncated",text.length()>256);
        } else if(val instanceof ObjectReference obj) {
            String id=s.stopId+":object:"+obj.uniqueID();
            if(s.references.containsKey(id) || s.references.size()<4096) {
                s.references.put(id,obj); out.put("reference",id);
            } else out.put("reference_unavailable","Stop-scoped reference limit reached (4096)");
            out.put("object_id",obj.uniqueID()).put("preview",val.type().name()+"#"+obj.uniqueID());
        } else out.put("preview",val.toString());
        return out;
    }
    private static ObjectNode variables(Session s,ObjectNode a) throws Exception {
        StackFrame f=frame(s,a); ThreadReference t=f.thread();
        List<ObjectNode> items=new ArrayList<>();
        if(f.thisObject()!=null) { ObjectNode o=value(s,f.thisObject()); o.put("name","this").put("kind","this"); items.add(o); }
        try {
            for(LocalVariable variable:f.visibleVariables()) {
                ObjectNode o=sensitive(variable.name()) && !bool(a,"include_sensitive_fields",false)
                    ? Json.MAPPER.createObjectNode().put("availability","redacted").put("preview","[REDACTED]")
                    : value(s,f.getValue(variable));
                o.put("name",variable.name()).put("declared_type",variable.typeName());
                o.put("kind",variable.isArgument()?"argument":"local"); items.add(o);
            }
        } catch(AbsentInformationException ex) { /* this remains inspectable without local-variable debug information */ }
        String filter=str(a,"filter",null); if(filter!=null) items.removeIf(x->!x.path("name").asText().contains(filter));
        int start=Math.min(cursor(a),items.size()), end=Math.min(items.size(),start+limit(a));
        ObjectNode out=node("ok",items.isEmpty()?"No visible locals (debug information may be absent)":"Visible frame variables")
            .put("stop_id",s.stopId).put("thread_id",Long.toString(t.uniqueID()))
            .put("frame_id",s.stopId+":"+t.uniqueID()+":"+t.frames().indexOf(f));
        ArrayNode arr=out.putArray("variables"); for(int i=start;i<end;i++) arr.add(items.get(i));
        out.put("truncated",end<items.size()); if(end<items.size()) out.put("next_cursor",end); return out;
    }
    private static ObjectNode object(Session s,ObjectNode a) {
        checkStop(s,a); String ref=str(a,"reference",null); required(ref,"reference");
        ObjectReference obj=s.references.get(ref);
        if(obj==null || !ref.startsWith(s.stopId+":object:")) throw fail("stale_reference","Object reference is not available at this stop; inspect variables again");
        ObjectNode out=node("ok","Object view (read-only, bounded)").put("stop_id",s.stopId).put("reference",ref)
            .put("type",obj.referenceType().name()).put("object_id",obj.uniqueID());
        int start=num(a,"start",cursor(a),Integer.MAX_VALUE), max=limit(a);
        if(num(a,"depth",1,8)>1) throw fail("unsupported","Nested object expansion is not a stable snapshot; follow stop-scoped references one level at a time");
        if(obj instanceof StringReference text) {
            String raw=text.value(); int from=Math.min(start,raw.length()), end=Math.min(raw.length(),from+Math.min(max,1024));
            out.put("value",raw.substring(from,end)).put("length",raw.length()).put("truncated",end<raw.length());
            if(end<raw.length()) out.put("next_cursor",end); return out;
        }
        ArrayNode fields=out.putArray("fields");
        if(obj instanceof ArrayReference array) {
            int end=Math.min(array.length(),start+max); for(int i=Math.min(start,array.length());i<end;i++) {
                ObjectNode entry=value(s,array.getValue(i)); entry.put("index",i); fields.add(entry);
            }
            out.put("length",array.length()).put("truncated",end<array.length()); if(end<array.length()) out.put("next_cursor",end);
        } else {
            List<Field> all=new ArrayList<>(bool(a,"include_inherited",true)?obj.referenceType().allFields():obj.referenceType().fields());
            if(!bool(a,"include_static",false)) all.removeIf(Field::isStatic);
            int end=Math.min(all.size(),start+max);
            for(int i=Math.min(start,all.size());i<end;i++) {
                Field field=all.get(i);
                ObjectNode entry=sensitive(field.name()) && !bool(a,"include_sensitive_fields",false)
                    ? Json.MAPPER.createObjectNode().put("availability","redacted").put("preview","[REDACTED]")
                    : value(s,field.isStatic()?field.declaringType().getValue(field):obj.getValue(field));
                entry.put("name",field.name()).put("declared_type",field.typeName()).put("declaring_class",field.declaringType().name()); fields.add(entry);
            }
            out.put("truncated",end<all.size()); if(end<all.size()) out.put("next_cursor",end);
        }
        return out;
    }
    private static ObjectNode source(Session s,ObjectNode a) {
        Location loc=frame(s,a).location(); String requested=str(a,"source_path",null);
        int line=num(a,"line",0,Integer.MAX_VALUE);
        if(line==0) {
            line=loc.lineNumber();
            if(line<1) throw fail("source_unavailable","Stopped frame has no line-number information; provide a positive line");
        }
        String relative;
        try { relative=loc.sourcePath(); } catch(AbsentInformationException e) { throw fail("source_unavailable","This frame has no source information; use its bytecode location"); }
        List<Path> matches=new ArrayList<>();
        if(requested!=null) {
            Path explicit=path(s.cwd,requested);
            if(Files.isRegularFile(explicit)) matches.add(explicit);
        } else for(String root:List.of("src/main/java","src/test/java")) {
            Path file=path(s.cwd,root).resolve(relative).normalize(); if(Files.isRegularFile(file)) matches.add(file);
        }
        if(matches.size()!=1) throw fail(matches.isEmpty()?"source_unavailable":"ambiguous_source",matches.isEmpty()?"Source unavailable; provide a source_path":"Multiple source files match; provide source_path: "+matches);
        if(requested!=null && !matches.getFirst().getFileName().toString().equals(Path.of(relative).getFileName().toString()))
            throw fail("source_mismatch","Requested source does not match the stopped frame's source filename");
        Path file=matches.getFirst();
        if(!file.endsWith(Path.of(relative))) throw fail("source_mismatch","Requested source path does not match the stopped frame's package-relative path");
        try {
            if(Files.size(file)>256_000) throw fail("source_unavailable","Source file is too large for a bounded debugger source read");
            List<String> lines=Files.readAllLines(file);
            if(line<1 || line>lines.size()) throw fail("source_mismatch","Stopped location is beyond the current source file; source may have changed since compilation");
            int before=num(a,"before",3,30), after=num(a,"after",3,30);
            int from=Math.max(1,line-before), end=Math.min(lines.size(),line+after);
            ObjectNode out=node("ok","Source around line "+line).put("stop_id",s.stopId).put("source_path",file.toString())
                .put("line",line).put("location_source_path",relative); ArrayNode arr=out.putArray("lines");
            for(int i=from;i<=end;i++) arr.addObject().put("line",i).put("text",lines.get(i-1).substring(0,Math.min(512,lines.get(i-1).length())));
            out.put("truncated",end<lines.size() || from>1);
            out.put("source_bytecode_verified",false); return out;
        } catch(IOException e) { throw fail("source_unavailable",e.getMessage()); }
    }
    private static ObjectNode exception(Session s,ObjectNode a) {
        checkStop(s,a); if(s.exceptionEvent==null) throw fail("no_exception","The current stop was not caused by an exception");
        ObjectReference ex=s.exceptionEvent.exception(); ObjectNode out=node("ok","Exception at throw site").put("stop_id",s.stopId)
            .put("type",ex.referenceType().name()).put("thread_id",Long.toString(s.stoppedThread.uniqueID()));
        out.set("throw_site",location(s,s.exceptionEvent.location())); out.set("exception",value(s,ex));
        Field message=ex.referenceType().fieldByName("detailMessage");
        if(message!=null && ex.getValue(message) instanceof StringReference text)
            out.put("message",text.value().substring(0,Math.min(512,text.value().length())));
        ArrayNode frames=out.putArray("stack");
        try {
            List<StackFrame> all=s.stoppedThread.frames();
            int start=Math.min(cursor(a),all.size()), end=Math.min(all.size(),start+limit(a));
            for(int i=start;i<end;i++) frames.add(location(s,all.get(i).location()));
            out.put("stack_truncated",end<all.size());
            if(end<all.size()) out.put("next_cursor",end);
        } catch(IncompatibleThreadStateException ignored) { out.put("stack_unavailable",true); }
        ArrayNode suppressed=out.putArray("suppressed");
        Field suppressedField=ex.referenceType().fieldByName("suppressedExceptions");
        if(suppressedField!=null && ex.getValue(suppressedField) instanceof ObjectReference list) {
            Field elements=list.referenceType().fieldByName("elementData");
            Field size=list.referenceType().fieldByName("size");
            if(elements!=null && size!=null && list.getValue(elements) instanceof ArrayReference array
                && list.getValue(size) instanceof IntegerValue count) {
                int suppressedCount=Math.min(count.value(),array.length());
                int start=Math.min(num(a,"suppressed_cursor",0,Integer.MAX_VALUE),suppressedCount),
                    end=Math.min(suppressedCount,start+limit(a));
                for(int i=start;i<end;i++) suppressed.add(value(s,array.getValue(i)));
                out.put("suppressed_truncated",end<suppressedCount);
                if(end<suppressedCount) out.put("next_suppressed_cursor",end);
            }
        }
        ArrayNode chain=out.putArray("causes"); Set<Long> seen=new HashSet<>(); ObjectReference current=ex;
        for(int i=0;i<Math.min(8,num(a,"depth",3,8)) && current!=null && seen.add(current.uniqueID());i++) {
            Field cause=current.referenceType().fieldByName("cause");
            if(cause==null || !(current.getValue(cause) instanceof ObjectReference next) || next==current) break;
            chain.add(value(s,next)); current=next;
        }
        return out;
    }
    private static void validateExpression(String expr) {
        try { ReadOnlyExpression.validate(expr); }
        catch(ReadOnlyExpression.Failure invalid) { throw fail(invalid.code,invalid.getMessage()); }
    }
    private static ReadOnlyExpression.Result evaluateValue(StackFrame frame,String expression) {
        try { return ReadOnlyExpression.evaluate(frame,expression); }
        catch(ReadOnlyExpression.Failure invalid) { throw fail(invalid.code,invalid.getMessage()); }
    }
    private static ObjectNode value(Session s,ReadOnlyExpression.Result result) {
        if(result.literalString()==null) return value(s,result.value());
        String text=result.literalString();
        return Json.MAPPER.createObjectNode().put("type","java.lang.String").put("availability","available")
            .put("preview",text.substring(0,Math.min(256,text.length()))).put("truncated",text.length()>256);
    }
    private static boolean truth(ReadOnlyExpression.Result v) { return v.value() instanceof BooleanValue b && b.booleanValue(); }
    private static ObjectNode evaluate(Session s,ObjectNode a,java.util.function.BooleanSupplier cancelled) {
        boolean single=a.hasNonNull("expression"), batch=a.hasNonNull("expressions");
        if(single==batch) throw fail("invalid_argument","Specify exactly one expression or expressions");
        List<String> expressions=new ArrayList<>();
        if(single) {
            if(!a.path("expression").isTextual()) throw fail("invalid_argument","expression must be a string");
            expressions.add(a.path("expression").asText());
        } else {
            JsonNode list=a.path("expressions");
            if(!list.isArray() || list.isEmpty() || list.size()>20)
                throw fail("invalid_argument","expressions must be an array of 1-20 strings");
            for(JsonNode item:list) {
                if(!item.isTextual()) throw fail("invalid_argument","expressions must contain only strings");
                expressions.add(item.asText());
            }
        }
        // Validate the entire request's size before doing any target reads.
        for(String expression:expressions) {
            if(expression.isBlank()) throw fail("invalid_argument","Expressions must not be blank");
            if(expression.codePointCount(0,expression.length())>256) throw fail("unsafe_expression","Read-only expressions are limited to 256 characters");
        }
        if(bool(a,"allow_side_effects",false)) throw fail("unsupported","Mutation/method invocation is not implemented; read-only evaluation is available without allow_side_effects");
        int budget=num(a,"timeout_ms",0,30000);
        long end=budget==0?Long.MAX_VALUE:System.nanoTime()+budget*1_000_000L;
        StackFrame f=frame(s,a);
        ObjectNode out=node("ok",single?"Read-only expression evaluated without method calls or mutation":"Read-only expressions evaluated in input order")
            .put("stop_id",s.stopId).put("thread_id",Long.toString(f.thread().uniqueID())).put("safety_level","read_only");
        ArrayNode results=single?null:out.putArray("results");
        int errors=0;
        for(String expression:expressions) {
            ObjectNode item;
            try {
                if(cancelled.getAsBoolean()) throw fail("cancelled","Evaluation cancelled; target execution was not changed");
                if(budget>0 && System.nanoTime()>=end) throw fail("timeout","Evaluation scheduling budget expired; target execution was not changed");
                item=Json.MAPPER.createObjectNode().put("status","ok").put("expression",expression);
                item.set("value",value(s,evaluateValue(f,expression)));
            } catch(Failure invalid) {
                if(single) throw invalid;
                item=error(invalid.code,invalid.getMessage()).put("expression",expression);
                item.remove("summary");
                errors++;
            } catch(ObjectCollectedException unavailable) {
                if(single) throw unavailable;
                item=Json.MAPPER.createObjectNode().put("status","error").put("code","object_collected")
                    .put("message","Target object was collected; try another expression").put("expression",expression);
                errors++;
            }
            if(single) {
                out.put("expression",expression); out.set("value",item.path("value"));
            } else results.add(item);
        }
        if(batch) out.put("error_count",errors);
        return out;
    }
    /** Detaches rather than killing targets on runtime reset or close. An attached JVM is never terminated. */
    public void closeSessions() {
        for(Session s:sessions.values()) synchronized(s) {
            if(!s.closed) { s.closed=true; try { s.vm.dispose(); } catch(Exception ignored) {} s.state="completed"; s.stopId=null; s.notifyAll(); }
            s.captureOutput=false; s.output.clear(); s.events.clear();
            s.references.clear(); s.knownSecrets=List.of(); s.extraSecrets=List.of();
        }
        sessions.clear(); requests.clear(); requestArguments.clear();
    }
    @Override public void close() { closed=true; closeSessions(); }
}
