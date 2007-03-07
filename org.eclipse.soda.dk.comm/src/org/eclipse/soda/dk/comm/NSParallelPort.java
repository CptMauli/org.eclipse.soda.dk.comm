package org.eclipse.soda.dk.comm;
/*******************************************************************************
 * Copyright (c) 1999, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
import javax.comm.*;
import java.io.*;
import java.util.*;

public class NSParallelPort extends ParallelPort
{
//-----------------------------------------------------------------------------
// Variables
//-----------------------------------------------------------------------------

  public static final int LPT_MODE_ANY = 0;
  public static final int LPT_MODE_SPP = 1;
  public static final int LPT_MODE_PS2 = 2;
  public static final int LPT_MODE_EPP = 3;
  public static final int LPT_MODE_ECP = 4;
  public static final int LPT_MODE_NIBBLE = 5;

  private int mode = LPT_MODE_SPP; // only SPP mode is supported at this time

  int fd = -1;                     // file descriptor for the open device
  FileDescriptor FD = null;        // FileDescriptor for the open device
                                   // for which buffers can be built upon

  private NSDeviceInputStream    ins = null;
  private NSDeviceOutputStream  outs = null;

  int rcvThreshold = -1;
  int rcvTimeout   = -1;

  boolean rcvFraming = false;
  int     rcvFramingByte;
  boolean rcvFramingByteReceived;

  /********* Disable read buffering for now *********/
  // int insBufferSize = 4096;
  int insBufferSize = 0;
  int insBufferCount = 0;

  boolean outsSuspended   = false;
  /********* Disable write buffering for default *********/
  // int     outsBufferSize  = 4096;
  int     outsBufferSize = 0;
  int     outsBufferCount = 0;

  private ParallelPortEventListener    listener = null;

  private boolean notifyOnErrorFlag  = false;
  boolean notifyOnBufferFlag = false;

  private ParallelErrorEventThread    errorThread = null;

  private DeviceListEntry dle = null;
  private NSCommDriver cd = null;


//-----------------------------------------------------------------------------
// Methods - constructors
//-----------------------------------------------------------------------------

  NSParallelPort(final String portName, final NSCommDriver driver) throws IOException {

    // NSParallelPort-extends-ParallelPort-extends-CommPort->name
    this.name = portName;
    // save CommDriver
    this.cd = driver;
  
    // look for portName in DeviceList
    for (DeviceListEntry cur = this.cd.getFirstDLE();
	 cur != null;
	 cur = this.cd.getNextDLE(cur))
    {
      if( cur.logicalName.equals(portName) )
      {
        // found the portName in list, attempt to open it using native method.
        if( (this.fd == -1) || !cur.opened )
        {
          if ( (this.fd = openDeviceNC( cur.physicalName, cur.semID )) == -1 )
          {
            // file descriptor is NOT valid, throw an Exception
            throw new IOException();
          } else
          {
            // Got a good file descriptor.
            // keep a copy of the DeviceListEntry where you found the portName
            // get a FileDescriptor object
            // turn opened ON
            this.dle = cur;
            this.dle.opened = true;
          }
        } else
        {
          throw new IOException();
        }

	break;	// found our port
      }
  
    }
  
  }

//-----------------------------------------------------------------------------
// Methods - public
//-----------------------------------------------------------------------------

  public InputStream getInputStream() throws IOException {

    if( this.ins == null )
    {
      if( (this.ins = new NSDeviceInputStream(this, this.dle.portType)) == null ) {
		throw new IOException();
	}
      this.ins.fd = this.fd;
    }

    return this.ins;
  }

  public OutputStream getOutputStream() throws IOException {

    if( this.outs == null )
    {
      if( (this.outs = new NSDeviceOutputStream(this, this.dle.portType)) == null ) {
		throw new IOException();
	}
      this.outs.fd = this.fd;
    }

    return this.outs;
  }

  public void close() {

    // check if either fd or opened is not valid
    // nothing to be done
    if( this.fd == -1 ) {
		return;
	}
  
    // if the error thread is alive, kill it
    if( this.errorThread != null )
    {
		this.errorThread.setStopThreadFlag(1);
//      stopThread(errorThread);
      this.errorThread = null;
      this.notifyOnErrorFlag = false;
    }
  
    // check ins and outs
    if( this.outs != null)
    {
      try
      {
        this.outs.flush();
      } catch (final IOException e)
      {
        e.printStackTrace();
      }
      this.outs = null;
    }
  
    if( this.ins != null ) {
		this.ins = null;
	}
  
    // close the device.
    closeDeviceNC(this.fd, this.dle.semID);

    // reset fd and opened.
    this.fd = -1;
    this.dle.opened = false;

    // close the commport
    super.close();

  }

  protected void finalize() throws IOException {
    close();
  }


  public void enableReceiveThreshold(final int thresh) throws UnsupportedCommOperationException {
    if( thresh > 0 ) {
		this.rcvThreshold = thresh;
	}
  }

  public void disableReceiveThreshold() {
    this.rcvThreshold = -1;
  }

  public boolean isReceiveThresholdEnabled() {

    return (this.rcvThreshold == -1 ? false : true);
  
  }

  public int getReceiveThreshold() {

    return this.rcvThreshold;

  }

  public void enableReceiveTimeout(final int rt) throws UnsupportedCommOperationException {
  
    if( rt > 0 ) {
		this.rcvTimeout = rt;
	} else if( rt == 0 ) {
		this.rcvTimeout = -1;
	}

  }

  public void disableReceiveTimeout() {

    this.rcvTimeout = -1;
  
  }

  public boolean isReceiveTimeoutEnabled() {

    return (this.rcvTimeout == -1 ? false : true);

  }
  

  public int getReceiveTimeout() {

    return this.rcvTimeout;

  }

  public void enableReceiveFraming(final int rcvFramingByte) throws UnsupportedCommOperationException {

    throw new UnsupportedCommOperationException();

  }

  public void disableReceiveFraming() {
    this.rcvFraming = false;
  }

  public boolean isReceiveFramingEnabled() {
    return this.rcvFraming;
  }

  public int getReceiveFramingByte() {
    return this.rcvFramingByte;
  }

  public void setInputBufferSize(final int size) {


  }

  public int getInputBufferSize() {
    return this.insBufferSize;
  }

  public void setOutputBufferSize(final int size) {

    if( size >= 0 ) {
		this.outsBufferSize = size;
	}

  }

  public int getOutputBufferSize() {
    return this.outsBufferSize;
  }

  public synchronized void addEventListener(final ParallelPortEventListener lst) throws TooManyListenersException {

    if (this.listener != null) {
		throw new TooManyListenersException();
	} else {

       this.listener = lst;

       if (this.notifyOnErrorFlag && (this.errorThread == null)) {
          this.errorThread = new ParallelErrorEventThread(this.fd, this);
          // errorThread.setDaemon( true ); // check it out
          this.errorThread.start();
       }
    }
  
  }

  public synchronized void removeEventListener() {

    if (this.listener != null) {
       if (this.errorThread != null) {
		this.errorThread.setStopThreadFlag(1);
	}
//	  stopThread(errorThread);
       this.errorThread = null;
       this.listener = null;
    }
  
  }

  public synchronized void notifyOnError(final boolean notify) {

    if( notify )
    {
      if( !this.notifyOnErrorFlag )
      {
        // instantiate ParallelErrorEventThread
        if( (this.errorThread == null) && (this.listener != null) )
        {
          this.errorThread = new ParallelErrorEventThread(this.fd, this);
          this.errorThread.start();
        }
        this.notifyOnErrorFlag = true;
      }
    } else
    {
      if( this.notifyOnErrorFlag )
      {
      	// Stop ParallelErrorEventThread
        if( this.errorThread != null ) {
			this.errorThread.setStopThreadFlag(1);
		}
//	        stopThread( errorThread );
        this.notifyOnErrorFlag = false;
        this.errorThread = null;
      }
    }

  }

  public synchronized void notifyOnBuffer(final boolean notify) {
    this.notifyOnBufferFlag = notify;
  }

  public int getOutputBufferFree() {

    return (this.outsBufferSize > this.outsBufferCount ?
	    this.outsBufferSize-this.outsBufferCount : 0);

  }

  public boolean isPaperOut() {return isPaperOutNC(this.fd);}

  public boolean isPrinterBusy() {return isPrinterBusyNC(this.fd);}

  public boolean isPrinterSelected() {return isPrinterSelectedNC(this.fd);}

  public boolean isPrinterTimedOut() {return isPrinterTimedOutNC(this.fd);}

  public boolean isPrinterError() {return isPrinterErrorNC(this.fd);}

  public void restart() {

    this.outsSuspended = false;
  
  }

  public void suspend() {

    this.outsSuspended = true;
  
  }

  public int getMode() {
    return this.mode;
  }

  public int setMode(final int md) throws UnsupportedCommOperationException {

    throw new UnsupportedCommOperationException();

  }

//-----------------------------------------------------------------------------
// Methods - private
//-----------------------------------------------------------------------------

  private native int openDeviceNC(String deviceName, int semID);

  private native int closeDeviceNC(int fd, int semID);
/*
  private synchronized void stopThread(Thread  th) {

     final int	maxcounter = 	30;
     int 	counter	=	0;
     boolean	done =		false;

     while (!done && counter < maxcounter) {

	// Interrupt the thread every 5 seconds.
	if ((counter++ % 5) == 0) {
	   
	   th.interrupt();

	}

	if (!th.isAlive())
	   done = true;
	else {

	   try {
	      
	      Thread.sleep(1000);	// sleep for a second

	   }
	   catch (InterruptedException e) {
	   }

	}

     }

     if (!done) {
		th.stop();
     }
  
  }
*/
  private native boolean isPaperOutNC(int fd);

  private native boolean isPrinterBusyNC(int fd);

  private native boolean isPrinterSelectedNC(int fd);

  private native boolean isPrinterTimedOutNC(int fd);

  private native boolean isPrinterErrorNC(int fd);

//-----------------------------------------------------------------------------
// Methods - package
//-----------------------------------------------------------------------------

  synchronized void reportParallelEvent(final int eventType, final boolean oldvalue, final boolean newvalue) {

     if (this.listener != null) {

	final ParallelPortEvent pe = new ParallelPortEvent(this, eventType, oldvalue,
						     newvalue);

	if (pe != null) {
		this.listener.parallelEvent(pe);
	}

     }
  
  }

}
