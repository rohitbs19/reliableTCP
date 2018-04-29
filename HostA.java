//import com.sun.corba.se.impl.encoding.ByteBufferWithInfo;

import javax.xml.crypto.Data;
import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;




public class HostA{



	//fields for the main class
	static int data_size = 988;			// (checksum:8, seqNum:4, data<=988) Bytes : 1000 Bytes total
	static int win_size = 10;
	static int timeoutVal = 300;		// 120ms until timeout

	int base;					// base sequence number of window
	int nextSeqNum;				// next sequence number in window
	String path;				// path of file to be sent
	String fileName;			// filename to be saved by receiver
	Vector<Packet> packetsList;	// list of generated packets
	Timer timer;				// for timeouts
	Semaphore s;				// guard CS for base, nextSeqNum
	boolean bufferEndLoopExit;// if receiver has completely received the file [INITIALIZE THIS WITH FALSE IN THE START]
	 int PACKETSIZE = 1024;
	 int ACKSIZE = 14;
	//another class for packet

    public void print(String s) {
        System.out.println(s);
    }
    public void setTimer(boolean isNewTimer){
        if (timer != null) timer.cancel();
        if (isNewTimer){
            timer = new Timer();
            timer.schedule(new Timeout(), timeoutVal);
        }
    }

    public class Timeout extends TimerTask{
        public void run(){
            try{
                s.acquire();	/***** enter CS *****/
                System.out.println("Sender: Timeout!");
                nextSeqNum = base;	// resets nextSeqNum
                s.release();	/***** leave CS *****/
            } catch(InterruptedException e){
                e.printStackTrace();
            }
        }
    }

    public class Packet{

        //fields
        int seqNumber;
        byte[] dataSeg;
        char flag;
        int ackNum;

        //constructor
        public Packet() {

        }
        public Packet(int seqNumber, byte[] dataSeg, char flag, int ackNum) {
            this.seqNumber = seqNumber;
            this.dataSeg = dataSeg;
            this.flag = flag;
            this.ackNum = ackNum;
        }
        public void generatePacketFromDatagramPacket(byte[] inData) {
            byte[] sequenceNumber = copyOfRange(inData, 0, 4);
            this.seqNumber = ByteBuffer.wrap(sequenceNumber).getInt();


            byte[] ackNumber = copyOfRange(inData, 4, 8);
            this.ackNum = ByteBuffer.wrap(ackNumber).getInt();
            //print("ackNUM " +this.ackNum);

            byte[] lengthField = copyOfRange(inData, 8, 12);
            int length = ByteBuffer.wrap(lengthField).getInt();

           /* int shiftedLength = length << 3;
            if ((shiftedLength + 4)==length) {
                this.flag = 'S';
            } else if ((shiftedLength + 2) == length) {
                this.flag='F';
            } else if ((shiftedLength + 1) == length) {
                this.flag = 'A';
            }*/
           int i=0;
            byte[] type = copyOfRange(inData, 12, 14);
           //print("type = " + type);
            this.flag = ByteBuffer.wrap(type).getChar();
            //System.out.println("INSIDE GENERATEPACKET FROM DATAGRAM FLAG:  " + this.flag);

            byte[] dataField = copyOfRange(inData, 14, inData.length);
            this.dataSeg = dataField;
        }

        public ByteBuffer createPacket() {
            // sequence number

            byte[] seqNumberBb = ByteBuffer.allocate(4).putInt(this.seqNumber).array();

            // timestamp
            //byte[] timestamp = ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array();

            byte[] ackNumBB = ByteBuffer.allocate(4).putInt(this.ackNum).array();

            // length

            int length=0;
            if (this.dataSeg != null) {
                length = this.dataSeg.length;
            }else{
                length=0;
            }
            // depending on the flag, we will set the packet with the appropriate flag


			/*int bitFlag = 0;

			if(flag=='S') {
				bitFlag = 4;
			} else if(flag == 'F') {
				bitFlag = 2;
			} else if(flag=='A') {
				bitFlag = 1;
			}


			// shifting the length to accomodate for SFA
			length = length << 3;
			length = length + bitFlag;*/

            //  convert to byte array
            byte[] dataLength = ByteBuffer.allocate(4).putInt(length).array();
            byte[] type = ByteBuffer.allocate(2).putChar(this.flag).array();
            byte[] data =null;
            int lengthDataSegment=0;
            if(this.dataSeg!=null) {
                data = ByteBuffer.allocate(this.dataSeg.length).put(this.dataSeg).array();
                lengthDataSegment = this.dataSeg.length;
            }
            // cumulative packet containing all data
            ByteBuffer packet = ByteBuffer.allocate(4 + 4 + 4 + 2+lengthDataSegment);
            packet.put(seqNumberBb);
            packet.put(ackNumBB);
            packet.put(dataLength);
            packet.put(type);
            if (this.dataSeg != null) {
                packet.put(data);
            }// handling data
            return packet;
        }

        public boolean isSynack() {
            if (this.flag == 'Q') {
                return true;
            }
            return false;
        }

        public byte [] getDataSeg() {

            return this.dataSeg;
        }

        public void setDataSeg(byte [] fileData) {

            this.dataSeg = fileData;
        }
        public void packetString() {
            System.out.println("***************PACKET1*******************");
            System.out.println("SEQ NUM: "+this.seqNumber);
            System.out.println("ACK NUM: "+this.ackNum);
            System.out.println("PACKET TYPE: "+this.flag);
            if (dataSeg != null) {
                System.out.println("DATA LENGTH: " + this.dataSeg.length);
            }
            System.out.println("***************PACKET1*******************");
        }

    }

	//send thread class

	public class senderThread extends Thread{

		//fields fir sender Thread
		private DatagramSocket out;
		private int dest_port;
		private InetAddress dest_addr;
		private int recv_port;

		//construct for sender Thread
		public senderThread(DatagramSocket socket_out, int dest_port, int recv_port) {
			this.out = socket_out;
			this.dest_port = dest_port;
			this.recv_port = recv_port;
		}

		//generate packet function used as helper function


		// main run method from Thread class
		//override


		public void run() {
			try {
				dest_addr = InetAddress.getByName("127.0.0.1");
				boolean isTransmitted_handshake = false; // ensures 16 retransmissions, else reports error
				int numRetransmissions = 0;
				FileInputStream fileInStream = new FileInputStream(new File(path));

				while (!isTransmitted_handshake && numRetransmissions < 16) {
					try {

						// send the first syn packet
						//first it should send a syn packet to the receiver and wait
						//Thread.sleep()
						// if the catch block gets the apt interrupt and this thread wakes up
						// then send an Ack to the Syn + ACK received in the ReceiveAckThread
						// once this process ends set the flag for sending data packets to the receiver
						//this might cause null pointer error
						Packet pkt_instance = new Packet(0, null, 'S', 0);
						ByteBuffer syn_pkt = pkt_instance.createPacket();
						pkt_instance.packetString();
						out.send(new DatagramPacket(syn_pkt.array(), syn_pkt.array().length,
								dest_addr, dest_port));
						System.out.println("sent the first syn Packet; SYN PACKET #" + pkt_instance.seqNumber);

						// THREE-WAY HANDSHAKE
						try {
							sleep(120);
							numRetransmissions++;

							// if the thread is interruputed, this means that
							// we have received an SYN+ACK from the receiver. Hence.
							// we can continue with normal execution i.e send an ACK
							// and start transmitting data.

						} catch (InterruptedException ex) {

                            System.out.println("IS INTERRUPTED");

							//now that we received the SYN + ACK from the receiver
							//send an ACK to the Receiver with ACK number = Receive SYN + 1
							//Here this will be 1 as the Receive SYN =0
                            boolean ackHandShake = false;
                            while (!ackHandShake) {


                                try {
                                    isTransmitted_handshake = true; // successfully transmitted
                                    Packet ackPkt_instance = new Packet(0, null, 'A', 1);
                                    ByteBuffer ackPkt = ackPkt_instance.createPacket();
                                    out.send(new DatagramPacket(ackPkt.array(), ackPkt.array().length, dest_addr, dest_port));
                                    System.out.println("the final ack for the handshake has been sent");
                                    //Thread.sleep(10000);
                                    sleep(120);
                                    ackHandShake = true;
                                    //Three way handshake done!

                                    // now keep a loop which run until the given file is retransmitted
                                    int terminationFlag = 0;    // terminates the loop when reading the file is done!
                                    while (!bufferEndLoopExit) {
                                        /*
                                         * 1: Till this point the three way handshake is done and the connection is established
                                         * 2: Now, There have to be two conditions:
                                         * 2-1: if --> base (first byte un-acked or the start of the send window/buffer),
                                         * 2-1: base < nextseqnum (this is end pointer of the buffer till where it is populated) then
                                         * aside: the timeout should equal the nextseqnum = base; as the base is first unacked byte
                                         * 2-1: retransmit the packet from the packet list (buffer) at nextseqNum (base)
                                         * 2-2: else that is if the nextSeqNum is > than pkt size
                                         * 2-2: then generate the next slice of the data and send to the receiver.
                                         *
                                         * */
                                        // empty data seg that is to be initialized with required data to be sent
                                        if(nextSeqNum < base + win_size) {
                                            s.acquire();

                                            if (base == nextSeqNum) {
                                                setTimer(true);
                                            }
                                            byte[] dataRead = new byte[PACKETSIZE-14];
                                            Packet newDataPktInstance = null;
                                            /*
                                             * NOTE:	that I have changed the packetlist to contain elements of Packet type
                                             * */
                                            if (nextSeqNum < packetsList.size()) {
                                                newDataPktInstance = packetsList.get(nextSeqNum);
                                            }
                                            // if normal case and not retransmission
                                            else {
                                                // read the slice of the file (set as 1024 for convenience now but has to be
                                                // changed to MTU and be taken an command line arg)
                                                terminationFlag = fileInStream.read(dataRead);
                                                if (terminationFlag == -1) {
                                                    bufferEndLoopExit = true;
                                                    newDataPktInstance = new Packet(nextSeqNum, new byte[0], 'F', -2);
                                                }
                                                //CAUTION flag is 'n'
                                                else {
                                                    newDataPktInstance = new Packet(nextSeqNum, dataRead, 'n', 0);
                                                }// add the new packet to be sent into the unacked packets buffer
                                                packetsList.add(newDataPktInstance);
                                                // this might cause null pointer
                                            }

                                            ByteBuffer normalPkt = newDataPktInstance.createPacket();
                                            out.send(new DatagramPacket(normalPkt.array(), normalPkt.array().length, dest_addr, dest_port));
                                            System.out.println("PACKET SENT SYN #" + newDataPktInstance.seqNumber);

                                            if (!bufferEndLoopExit) {
                                                nextSeqNum++;
                                            }
                                            s.release();

                                        }
                                        Thread.sleep(5);
                                    }

                                } catch (InterruptedException ip) {
                                    ip.printStackTrace();
                                    Packet ackPkt_instance = new Packet(0, null, 'A', 1);
                                    ByteBuffer ackPkt = ackPkt_instance.createPacket();
                                    out.send(new DatagramPacket(ackPkt.array(), ackPkt.array().length, dest_addr, dest_port));
                                    System.out.println("the final ack for the handshake has been sent");
                                }
                            }
						}


					} catch (Exception exc) {
						exc.printStackTrace();
					}
				}

			} catch (Exception exc) {
				exc.printStackTrace();
			}

		}

	}



    public byte[] copyOfRange(byte[] srcArr, int start, int end){
        int length = (end > srcArr.length)? srcArr.length-start: end-start;
        byte[] destArr = new byte[length];
        System.arraycopy(srcArr, start, destArr, 0, length);
        return destArr;
    }



	//receive thread class
	public class receiveAckThread extends Thread{

		//fields
		private DatagramSocket in;
        senderThread ThreadSender;
		//constructor
		public receiveAckThread(DatagramSocket in, senderThread ThreadSender ) {
			this.in = in;
            this.ThreadSender = ThreadSender;

		}


		//generate packet function used as helper function
		// see if you can make use of the existing packet class
		///actually we cannot and we need to unwrap the byte array we receive from the receiver
		public int ackNumExtract(byte[] receivedPacket) {
            byte[] ackBytes = copyOfRange(receivedPacket, 4, 8);
			return ByteBuffer.wrap(ackBytes).getInt(); // returns -1 if the checksum computation for the received ack fails
			          // return -2 if the ack indicated teardown
		}

       /* public boolean isSYNACK(byte[] data) {
            byte[] lengthField = copyOfRange(data, 8, 12);
            int length = ByteBuffer.wrap(lengthField).getInt();
           *//* int shiftedLength = length << 3;
            if ((shiftedLength + 5)==length) {
                return true;
            }*//*
            byte[] type = copyOfRange(data, 12, 14);

            if (ByteBuffer.wrap(type).getChar() == 'Q') {
                return true;
            }
		    return false;
        }*/
		//run method override
		public void run() {
			try {

				byte[] receivedAckData = new byte[ACKSIZE];
				DatagramPacket receivedPacket = new DatagramPacket(receivedAckData, receivedAckData.length);

				try {

					while (!bufferEndLoopExit) {
						in.receive(receivedPacket);
                        Packet receivedAck = new Packet();
                        receivedAck.generatePacketFromDatagramPacket(receivedAckData);
                        //receivedAck.packetString();

						int ackNumberFromPkt = ackNumExtract(receivedAckData);
                        System.out.println("isSYNACK  " + receivedAck.isSynack());
						if (receivedAck.isSynack()) {
                            //wake the sleeping thread as the required packet has arrived
                            this.ThreadSender.interrupt();

                        }
						/*
						*
						* 1: check if dup ack
						* 1-2: if dup ack THEN increment dup ack counter;
						* 2: if dup counter reached retransmit the packet : that retransmit condition nextseqnum = base;
						* 3: check if the ack is normal [THIS INCLUDES should take check for teardown signal into consideration]
						* 3-1: if ack is normal equate the ack = base;
						* 3-2:
						*
						*
						* */
						else {
                            if (ackNumberFromPkt != -1) {
                                //dup ack detected
                                if (base == ackNumberFromPkt+1) {
                                    //then retransmit condition that is
                                    s.acquire();
                                    setTimer(false);
                                    nextSeqNum = base;
                                    s.release();
                                } else if (ackNumberFromPkt == -2) {
                                    //probably have to do complete fin shake
                                    bufferEndLoopExit = true;
                                } else {
                                    base = ackNumberFromPkt++;
                                    s.acquire();
                                    if (base == nextSeqNum) {
                                        setTimer(false);
                                    }else{
                                        setTimer(true);
                                    }
                                    s.release();
                                    //refresh the timer for this
                                }

                            }
                        }
					}

				} catch (Exception ex) {
					ex.printStackTrace();
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}


	}

	//constructor for the main class
	public HostA(int sk1_dst_port, int sk4_dst_port, String path, String fileName) {
		base = 0;
		nextSeqNum = 0;
		this.path = path;
		this.fileName = fileName;
		packetsList = new Vector<Packet>(win_size);
		bufferEndLoopExit = false;
		DatagramSocket sk1, sk4;
		s = new Semaphore(1);
		System.out.println("Sender: sk1_dst_port=" + sk1_dst_port + ", sk4_dst_port=" + sk4_dst_port + ", inputFilePath=" + path + ", outputFileName=" + fileName);

		try {
			// create sockets
			sk1 = new DatagramSocket();				// outgoing channel
			sk4 = new DatagramSocket(sk4_dst_port);	// incoming channel

			// create threads to process data

			senderThread th_out = new senderThread(sk1, sk1_dst_port, sk4_dst_port);
            receiveAckThread th_in = new receiveAckThread(sk4, th_out);
			th_in.start();
			th_out.start();

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	//main method for the main class
	public static void main(String args[]) {
		if (args.length != 4) {
			System.err.println("Usage: java Sender sk1_dst_port, sk4_dst_port, inputFilePath, outputFileName");
			System.exit(-1);
		}
		else new HostA(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], args[3]);
	}


}