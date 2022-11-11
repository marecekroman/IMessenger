package com.roman;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;

class ActiveHandlers {
    private static final long serialVersionUID = 1L;
    private HashSet<SocketHandler> activeHandlersSet=new HashSet<SocketHandler>();


    /** createMessage - Joins array into client's message
     * @param rawMessage - message array
     */
    String createMessage(String[] rawMessage){
        String message="";

        for(int i = 2; i< rawMessage.length; i++){
            message+= rawMessage[i] + " ";
        }
        return message;
    }


    /** performRequest - Process client's message
     * @param sender - sender's reference
     * @param message - message string
     */
    synchronized void performRequest(SocketHandler sender, String message) {
        String[] command;

        //Checks for command pattern
        if(message.startsWith("#")) {
            command = message.split(" ");
        } else {
            this.sendMessageToAll(sender,message);
            return;
        }

        switch (command[0]){

            case ("#PM"):{
                // Sends private message
                if(command.length > 1 ) {
                    this.sendMessagePrivate(sender, command[1], createMessage(command));
                } else {
                    sender.messages.offer("Missing receiver's ID");
                }
                break;
            }

            case ("#CN"):{
                //Changes nickname
                if(command.length > 1 ) {
                    this.changeID(sender, command[1]);
                } else {
                    sender.messages.offer("Missing new ID");
                }
                break;
            }

            case ("#CR"):
                //Creates room
                //TODO make implementation
                System.out.println("Create room");
                break;

            case ("#JR"):
                //Joins created room
                //TODO make implementation
                System.out.println("Join room");

            default:  System.out.println("Invalid command!");
            break;
        }
    }


    /** changeID - Change actual user's ID
     * @param sender - sender's reference
     * @param newID - string with new user's ID
     */
    synchronized void changeID(SocketHandler sender, String newID) {

        for (SocketHandler handler:activeHandlersSet)

            if (handler==sender) {

                if (handler.clientID==sender.clientID) {
                    this.sendMessageToAll(sender,"User " + handler.clientID + " changed ID to: " + newID + "\n");
                    handler.clientID=newID;
                }
            }
    }


    /** sendMessagePrivate - Sends message to specific user
     * @param sender -  sender's reference
     * @param receiverID - string with receiver's ID
     * @param message - message string
     */
    synchronized void sendMessagePrivate(SocketHandler sender, String receiverID, String message) {

        boolean found = false;

        for (SocketHandler handler:activeHandlersSet)

            if (handler.clientID.equals(receiverID)) {

                found = true;

                if (!handler.messages.offer(sender.clientID+" : "+message))
                    sender.messages.offer("Client " + handler.clientID + " message queue is full, dropping the message!\n");
            }

        if (!found){
            sender.messages.offer("Client " + receiverID + " doesn't exist, dropping the message!\n");
        }
    }


    /** sendMessageToAll - Sends message to all users except sender
     * @param sender - sender's reference
     * @param message - message string
     */
    synchronized void sendMessageToAll(SocketHandler sender, String message) {

        for (SocketHandler handler:activeHandlersSet)	// for all active handlers

            if (handler!=sender) {

                if (!handler.messages.offer(message))   // try to add message to receiver's queue
                    sender.messages.offer("Client " + handler.clientID + " message queue is full, dropping the message!\n");
            }
    }


    /** add - adds new handler to list of active handlers.
     * This method is synchronized, because HashSet can't handle with multithreading.
     * @param handler - reference to handler, which will be added.
     * @return true if the set did not already contain the specified element.
     */
    synchronized boolean add(SocketHandler handler) {
        return activeHandlersSet.add(handler);
    }


    /** remove - removes new handler from list of active handlers.
     * This method is synchronized, because HashSet can't handle with multithreading.
     * @param handler - reference to handler, which will be removed.
     * @return true if the set did not already contain the specified element.
     */
    synchronized boolean remove(SocketHandler handler) {
        return activeHandlersSet.remove(handler);
    }
}


class SocketHandler {


    /** mySocket is socket, o which will be cared by SocketHandler*/
    Socket mySocket;


    /** client ID is string in format <IP_address>:<port>	 */
    String clientID;


    /** activeHandlers is reference on list of all running handlers.
     *  We need to keep this to send message from this client
     *  to all other clients!
     */
    ActiveHandlers activeHandlers;


    /** messages is queue of incoming messages. Every client must have his own
     *  - if client's network is overloaded or unreachable,
     *  his messages waiting to delivery in this queue of messages.
     */
    ArrayBlockingQueue<String> messages=new ArrayBlockingQueue<String>(20);


    /** startSignal is synchronization barrier, which arranges to both tasks
     * OutputHandler.run() and InputHandler.run() starts at the same time.
     */
    CountDownLatch startSignal=new CountDownLatch(2);


    /** outputHandler.run() will be caring about OutputStream of mine socket */
    OutputHandler outputHandler=new OutputHandler();


    /** inputHandler.run()  will be caring about InputStream of mine socket */
    InputHandler inputHandler=new InputHandler();


    /** I'm using inputFinished, because I'm unable to detect socket close in outputHandler */
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
                    String m=messages.take();// blocking read - if message queue is empty, go sleep!
                    writer.write(m+"\r\n");    // if there are messages in queue, send them!
                    writer.flush();
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

                /** At the moment when Thread pool starts to run, we will add client to list
                 *  of all active handlers, to receive other clients messages
                 */
                activeHandlers.add(SocketHandler.this);
                BufferedReader reader=new BufferedReader(new InputStreamReader(mySocket.getInputStream(),"UTF-8"));

                while ((request=reader.readLine())!=null) { 		// Is input from client?
                    activeHandlers.performRequest(SocketHandler.this,request);
                    request="From client "+clientID+": "+request;
                    System.out.println(request);
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

    public static class Rooms{
//todo dummy collection (usersID, Name of room)
       public String[] roomsName;
    }

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


