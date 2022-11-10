package com.roman;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;


class ActiveHandlers {
    private static final long serialVersionUID = 1L;
    private HashSet<SocketHandler> activeHandlersSet=new HashSet<SocketHandler>();

    String createMessage(String[] rawMessage){
        String message="";

        for(int i = 2; i< rawMessage.length; i++){
            message+= rawMessage[i] + " ";
        }
        return message;
    };

    /** performRequest - Pošle zprávu všem aktivním klientům kromě sebe sama
     * @param sender - reference odesílatele
     * @param message - řetězec se zprávou
     */
    synchronized void performRequest(SocketHandler sender, String message) {
        String[] command;

        if(message.startsWith("#")) {
            command = message.split(" ");
        } else {
            this.sendMessageToAll(sender,message);
            return;
        }

        switch (command[0]){
            case ("#PM"):{
                if(command.length > 1 ) {
                    this.sendMessagePrivate(command[1], createMessage(command));
                } else {
                    this.sendMessagePrivate(sender.clientID, "Chybi ID prijemce");
                }
                break;
            }
            case ("#CH"):{
                if(command.length > 1 ) {
                    this.changeID(sender, command[1]);
                } else {
                    this.sendMessagePrivate(sender.clientID, "Chybi nove ID");
                }
                break;
            }
            case ("#CR"):
                System.out.println("vytvor mistnost");
                break;
            case ("#JR"):
                System.out.println("pripoj mistnost");

            default:  System.out.println("Neplatny prikaz");
            break;
        }
    }

    /** sendMessageToAll - Pošle zprávu všem aktivním klientům kromě sebe sama
     * @param sender - reference odesílatele
     * @param newID - řetězec se zprávou
     */
    synchronized void changeID(SocketHandler sender, String newID) {
        for (SocketHandler handler:activeHandlersSet)	// pro všechny aktivní handlery
            if (handler==sender) {
                if (handler.clientID==sender.clientID) {
                    this.sendMessageToAll(sender,"Uzivatel " + handler.clientID + " si zmenil ID na " + newID + "\n");
                    handler.clientID=newID;
                }
            }
    }

    /** sendMessagePrivate - Pošle zprávu všem aktivním klientům kromě sebe sama
     * @param receiverID - todo
     * @param message - řetězec se zprávou
     */
    synchronized void sendMessagePrivate( String receiverID, String message) {
        for (SocketHandler handler:activeHandlersSet)	// pro všechny aktivní handlery
            if (handler.clientID.contains(receiverID)) {
                if (!handler.messages.offer(message))   // zkus přidat zprávu do fronty jeho zpráv
                    System.err.printf("Client %s message queue is full, dropping the message!\n", handler.clientID);
            }
            else {
                System.err.printf("Client %s doesn't exist, dropping the message!\n", receiverID); // TODO Private
            }
    }
    /** sendMessageToAll - Pošle zprávu všem aktivním klientům kromě sebe sama
     * @param sender - reference odesílatele
     * @param message - řetězec se zprávou
     */
    synchronized void sendMessageToAll(SocketHandler sender, String message) {
        for (SocketHandler handler:activeHandlersSet)	// pro všechny aktivní handlery
            if (handler!=sender) {
                if (!handler.messages.offer(message))   // zkus přidat zprávu do fronty jeho zpráv
                    System.err.printf("Client %s message queue is full, dropping the message!\n", handler.clientID);
            }
    }
    /** add přidá do množiny aktivních handlerů nový handler.
     *  Metoda je sychronizovaná, protože HashSet neumí multithreading.
     * @param handler - reference na handler, který se má přidat.
     * @return true if the set did not already contain the specified element.
     */
    synchronized boolean add(SocketHandler handler) {
        return activeHandlersSet.add(handler);
    }

    /** remove odebere z množiny aktivních handlerů nový handler.
     *  Metoda je sychronizovaná, protože HashSet neumí multithreading.
     * @param handler - reference na handler, který se má odstranit
     * @return true if the set did not already contain the specified element.
     */
    synchronized boolean remove(SocketHandler handler) {
        return activeHandlersSet.remove(handler);
    }
}


class SocketHandler {
    /** mySocket je socket, o který se bude tento SocketHandler starat */
    Socket mySocket;

    /** client ID je řetězec ve formátu <IP_adresa>:<port>	 */
    String clientID;

    /** activeHandlers je reference na množinu všech právě běžících SocketHandlerů.
     *  Potřebujeme si ji udržovat, abychom mohli zprávu od tohoto klienta
     *  poslat všem ostatním!
     */
    ActiveHandlers activeHandlers;

    /** messages je fronta příchozích zpráv, kterou musí mít kažý klient svoji
     * vlastní  - pokud bude je přetížená nebo nefunkční klientova síť,
     * čekají zprávy na doručení právě ve frontě messages
     */
    ArrayBlockingQueue<String> messages=new ArrayBlockingQueue<String>(20);

    /** startSignal je synchronizační závora, která zařizuje, aby oba tasky
     * OutputHandler.run() a InputHandler.run() začaly ve stejný okamžik.
     */
    CountDownLatch startSignal=new CountDownLatch(2);

    /** outputHandler.run() se bude starat o OutputStream mého socketu */
    OutputHandler outputHandler=new OutputHandler();
    /** inputHandler.run()  se bude starat o InputStream mého socketu */
    InputHandler inputHandler=new InputHandler();
    /** protože v outputHandleru nedovedu detekovat uzavření socketu, pomůže mi inputFinished */
    volatile boolean inputFinished=false;

    public SocketHandler(Socket mySocket, ActiveHandlers activeHandlers) {
        this.mySocket=mySocket;
        clientID=mySocket.getInetAddress().toString()+":"+mySocket.getPort();
        this.activeHandlers=activeHandlers;
    }

    class OutputHandler implements Runnable {
        public void run() {
            OutputStreamWriter writer;
            try {
                System.err.println("DBG>Output handler starting for "+clientID);
                startSignal.countDown(); startSignal.await();
                System.err.println("DBG>Output handler running for "+clientID);
                writer = new OutputStreamWriter(mySocket.getOutputStream(), "UTF-8");
                writer.write("\nYou are connected from " + clientID+"\n");
                writer.flush();
                while (!inputFinished) {
                    String m=messages.take();// blokující čtení - pokud není ve frontě zpráv nic, uspi se!
                    writer.write(m+"\r\n");    // pokud nějaké zprávy od ostatních máme,
                    writer.flush();			 // pošleme je našemu klientovi
                    System.err.println("DBG>Message sent to "+clientID+":"+m+"\n");
                }
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            System.err.println("DBG>Output handler for "+clientID+" has finished.");

        }
    }

    class InputHandler implements Runnable {
        public void run() {
            try {
                System.err.println("DBG>Input handler starting for "+clientID);
                startSignal.countDown(); startSignal.await();
                System.err.println("DBG>Input handler running for "+clientID);
                String request="";
                /** v okamžiku, kdy nás Thread pool spustí, přidáme se do množiny
                 *  všech aktivních handlerů, aby chodily zprávy od ostatních i nám
                 */
                activeHandlers.add(SocketHandler.this);
                BufferedReader reader=new BufferedReader(new InputStreamReader(mySocket.getInputStream(),"UTF-8"));
                while ((request=reader.readLine())!=null) { 		// přišla od mého klienta nějaká zpráva?
                    // ano - pošli ji všem ostatním klientům
                    activeHandlers.performRequest(SocketHandler.this,request);
                    request="From client "+clientID+": "+request;
                    System.out.println(request);
                    //activeHandlers.sendMessageToAll(SocketHandler.this,request);
                }
                inputFinished=true;
                messages.offer("OutputHandler, wakeup and die!");
            } catch (UnknownHostException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                // remove yourself from the set of activeHandlers
                synchronized (activeHandlers) {
                    activeHandlers.remove(SocketHandler.this);
                }
            }
            System.err.println("DBG>Input handler for "+clientID+" has finished.");
        }

    }
}
public class Server {

    public static void main(String[] args) {
        int port=33000, max_conn=2;

        if (args.length>0) {
            if (args[0].startsWith("--help")) {
                System.out.printf("Usage: Server [PORT] [MAX_CONNECTIONS]\n" +
                        "If PORT is not specified, default port %d is used\n" +
                        "If MAX_CONNECTIONS is not specified, default number=%d is used",port, max_conn);
                return;
            }
            try {
                port=Integer.decode(args[0]);
            } catch (NumberFormatException e) {
                System.err.printf("Argument %s is not integer, using default value",args[0],port);
            }
            if (args.length>1) try {
                max_conn=Integer.decode(args[1]);
            } catch (NumberFormatException e) {
                System.err.printf("Argument %s is not integer, using default value",args[1],max_conn);
            }

        }
        // TODO Auto-generated method stub
        System.out.printf("IM server listening on port %d, maximum nr. of connections=%d...\n", port, max_conn);
        ExecutorService pool=Executors.newFixedThreadPool(2*max_conn);
        ActiveHandlers activeHandlers=new ActiveHandlers();

        try {
            ServerSocket sSocket=new ServerSocket(port);
            do {
                Socket clientSocket=sSocket.accept();
                clientSocket.setKeepAlive(true);
                SocketHandler handler=new SocketHandler(clientSocket, activeHandlers);
                pool.execute(handler.inputHandler);
                pool.execute(handler.outputHandler);
            } while (!pool.isTerminated());
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            pool.shutdown();
            try {
                // Wait a while for existing tasks to terminate
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!pool.awaitTermination(60, TimeUnit.SECONDS))
                        System.err.println("Pool did not terminate");
                }
            } catch (InterruptedException ie) {
                // (Re-)Cancel if current thread also interrupted
                pool.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }
    }
}


