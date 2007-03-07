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
import  javax.comm.*;

import  java.io.*;
import  java.util.*;

/**
    An RS-232 serial communications port. SerialPort describes the
    low-level interface to a serial communications port made
    available by the underlying system. SerialPort defines the
    minimum required functionality for serial communications ports.
*/
class NSSerialPort extends SerialPort
{

  public static final int DATABITS_5    = 5;
  public static final int DATABITS_6    = 6;
  public static final int DATABITS_7    = 7;
  public static final int DATABITS_8    = 8;

  public static final int STOPBITS_1    = 1;
  public static final int STOPBITS_2    = 2;
  public static final int STOPBITS_1_5  = 3;

  public static final int PARITY_NONE   = 0;
  public static final int PARITY_ODD    = 1;
  public static final int PARITY_EVEN   = 2;
  public static final int PARITY_MARK   = 3;
  public static final int PARITY_SPACE  = 4;

  public static final int FLOWCONTROL_NONE        = 0;
  public static final int FLOWCONTROL_RTSCTS_IN   = 1;
  public static final int FLOWCONTROL_RTSCTS_OUT  = 2;
  public static final int FLOWCONTROL_XONXOFF_IN  = 4;
  public static final int FLOWCONTROL_XONXOFF_OUT = 8;


  private int flowcontrol = FLOWCONTROL_NONE;
  private int baudrate = 9600;
  private int databits = DATABITS_8;
  private int stopbits = STOPBITS_1;
  private int parity = PARITY_NONE;

  private boolean dtr;
  private boolean rts;

  private DeviceListEntry dle = null;
  private NSCommDriver cd = null;

  int fd = -1;  // file descriptor for the open device
  FileDescriptor FD = null;  // FileDescriptor for the open device for which buffers can be built upon

  private NSDeviceInputStream ins = null;
  private NSDeviceOutputStream outs = null;

  int rcvThreshold = -1;
  int rcvTimeout = -1;

  boolean rcvFraming = false;
  int rcvFramingByte;
  boolean rcvFramingByteReceived;


  /********* Disable read buffering for now *********/
  // int insBufferSize = 4096;
  int insBufferSize = 0;
  int insBufferCount = 0;
  /********* Disable write buffering for default *********/
  // int outsBufferSize = 4096;
  int outsBufferSize = 0;
  int outsBufferCount = 0;

  private SerialPortEventListener    listener = null;

  private boolean notifyOnCTSFlag = false;
  private boolean notifyOnDSRFlag = false;
  private boolean notifyOnRIFlag  = false;
  private boolean notifyOnCDFlag  = false;
  private boolean notifyOnORFlag  = false;
  private boolean notifyOnPEFlag  = false;
  private boolean notifyOnFEFlag  = false;
  private boolean notifyOnBIFlag  = false;

          boolean notifyOnBufferFlag = false;
  private boolean notifyOnDataFlag   = false;

  private SerialStatusEventThread    statusThread = null;
  private SerialDataEventThread     dataThread = null;

  /**
     Constructor
   */
  public NSSerialPort(final String portName, final NSCommDriver driver)
         throws IOException
  {
     // caller wants port portName
    // NSSerialPort-extends-SerialPort-extends-CommPort->name
    this.name = portName;
    // save CommDriver
    this.cd = driver;

    // look for portName in DeviceList
    for (DeviceListEntry cur = this.cd.getFirstDLE(); cur != null; cur = this.cd.getNextDLE(cur))
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

  /**

   */
  public InputStream getInputStream()
         throws IOException
  {
    if( this.ins != null ) {
		return this.ins;
	} else
    {
      // Y: get a new deviceInputStream
      if( (this.ins = new NSDeviceInputStream(this, this.dle.portType)) == null ) {
		throw new IOException();
	} else
      {
    	this.ins.fd = this.fd;
        return this.ins;
      }
    }
  }

  /**
   */
  public OutputStream getOutputStream()
         throws IOException
  {
    if( this.outs != null ) {
		return this.outs;
	} else
    {
      // Y: get a new DeviceOutputStream
      if( (this.outs = new NSDeviceOutputStream(this, this.dle.portType)) == null ) {
		throw new IOException();
	} else
      {
        // what do I do here
	this.outs.fd = this.fd;
        return this.outs;
      }
    }
  }

  /**
   */
  public void close()
  {
    if( this.fd == -1 ) {
		return;
	}

    // if thread are alive, kill them
    if( this.statusThread != null )
    {
	  this.statusThread.setStopThreadFlag(1);	
//      stopThread(statusThread);
      this.notifyOnCTSFlag = false;
      this.notifyOnDSRFlag = false;
      this.notifyOnRIFlag  = false;
      this.notifyOnCDFlag  = false;
      this.notifyOnORFlag  = false;
      this.notifyOnPEFlag  = false;
      this.notifyOnFEFlag  = false;
      this.notifyOnBIFlag  = false;
    }

    if( this.dataThread != null )
    {
  	  this.dataThread.setStopThreadFlag(1);	  
//      stopThread(dataThread);
      this.notifyOnDataFlag = false;
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

  /**
   */
  public void enableReceiveThreshold(final int thresh)
         throws UnsupportedCommOperationException
  {
     if( thresh > 0 ) {
		this.rcvThreshold = thresh;
	}
  }

  /**
   */
  public void disableReceiveThreshold()
  {
     this.rcvThreshold = -1;
  }

  public boolean isReceiveThresholdEnabled()
  {
    if( this.rcvThreshold == -1 ) {
		return false;
	} else {
		return true;
	}
  }

  public int getReceiveThreshold()
  {
     return this.rcvThreshold;
  }

  public void enableReceiveTimeout(final int rt)
         throws UnsupportedCommOperationException
  {
    // System.out.println("-- Enter enableReceiveTimeout()");
    if( rt > 0 ) {
		this.rcvTimeout = rt;
	} else if( rt == 0 ) {
		this.rcvTimeout = -1;
	}
  }

  public void disableReceiveTimeout()
  {
    this.rcvTimeout = -1;
  }

  public boolean isReceiveTimeoutEnabled()
  {
    if( this.rcvTimeout == -1 ) {
		return false;
	} else {
		return true;
	}
  }

  public int getReceiveTimeout()
  {
    return this.rcvTimeout;
  }

  public void enableReceiveFraming(final int rcvFramingByte)
         throws UnsupportedCommOperationException
  {
    /********* Disable receive framing for now *********/
    // rcvFramingByte = rcvFramingByte;
    // rcvFraming = true;
    throw new UnsupportedCommOperationException();
  }

  public void disableReceiveFraming()
  {
    this.rcvFraming = false;
  }

  public boolean isReceiveFramingEnabled()
  {
    return this.rcvFraming;
  }

  public int getReceiveFramingByte()
  {
    return this.rcvFramingByte;
  }

  
  public int getBaudrate() {
	return this.baudrate;
}

public boolean isDtr() {
	return this.dtr;
}

public boolean isRts() {
	return this.rts;
}

public void setInputBufferSize(final int size)
  {
    /********* Disable read buffering for now *********/
    // if( size >= 0 )
    //   insBufferSize = size;
  }

  public int getInputBufferSize()
  {
    return this.insBufferSize;
  }

  public void setOutputBufferSize(final int size)
  {
     if( size >= 0 ) {
		this.outsBufferSize = size;
	}
  }

   public int getOutputBufferSize()
  {
    return this.outsBufferSize;
  }

  public synchronized void addEventListener(final SerialPortEventListener lstnr)
         throws TooManyListenersException
  {
     if (this.listener != null) {
		throw new TooManyListenersException();
	} else
    {

      this.listener = lstnr;
      // check all other related flags, all must be false
      if((this.notifyOnDSRFlag ||
          this.notifyOnRIFlag  ||
          this.notifyOnCDFlag  ||
          this.notifyOnORFlag  ||
          this.notifyOnPEFlag  ||
          this.notifyOnFEFlag  ||
          this.notifyOnCTSFlag ||
          this.notifyOnBIFlag ) && (this.statusThread == null) )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        // statusThread.setDaemon( true ); // check it out ???
        this.statusThread.start();
      }
      if( this.notifyOnDataFlag && (this.dataThread == null) )
      {
        this.dataThread = new SerialDataEventThread(this.fd, this);
        // dataThread.setDaemon( true ); // check it out ???
        this.dataThread.start();

      }
    }
  }

  public synchronized void removeEventListener()
  {
     if (this.listener != null)
    {
       if (this.statusThread != null) {
		this.statusThread.setStopThreadFlag(1);
	}
//          stopThread(statusThread);
       this.statusThread = null;

       if (this.dataThread != null) {
		this.dataThread.setStopThreadFlag(1);
	}
//          stopThread(dataThread);
       this.dataThread = null;

       this.listener = null;
    }
  }

  public int getBaudRate()
  {
    int bdrate = 0;

    if ( this.fd > -1 )
    {
      bdrate = getBaudRateNC( this.fd );
      if ( bdrate < 0 )
      {
        bdrate = 0;
      } else
      {
        this.baudrate = bdrate;
        // no need to map native values to java values here
      }
    }

    return bdrate;
  }

  public int getDataBits()
  {
    int db = 0;
    if ( this.fd > -1 )
    {
      db = getDataBitsNC( this.fd );
      if( db != -1 )
      {
        switch (db)
        {
          case 5:
            this.databits = DATABITS_5;
          break;
          case 6:
            this.databits = DATABITS_6;
          break;
          case 7:
            this.databits = DATABITS_7;
          break;
          case 8:
            this.databits = DATABITS_8;
          break;
        }
      }
    }

    return this.databits;
  }

  public int getStopBits()
  {
    int sb = 0;

    if ( this.fd > -1 )
    {
      sb = getStopBitsNC( this.fd );
      if ( sb != -1 )
      {
        switch (sb)
        {
		  case 0:
			  this.stopbits = STOPBITS_1_5;
			  break;
          case 1:
            this.stopbits = STOPBITS_1;
          	break;
          case 2:
            this.stopbits = STOPBITS_2;
         	break;
        }
      }
    }

    return this.stopbits;
  }

  public int getParity()
  {
    int p = 0;

    if ( this.fd > -1 )
    {
      p = getParityNC( this.fd );

      if( p != -1 )
      {
        switch (p)
        {
          case 0:
            this.parity = PARITY_NONE;
          break;
          case 1:
            this.parity = PARITY_ODD;
          break;
           case 2:
            this.parity = PARITY_EVEN;
          break;
		   case 3:
		    this.parity = PARITY_MARK;
		  break;
		   case 4:
		    this.parity = PARITY_SPACE;
		  break;
        }
      }
    }

    return this.parity;
  }

  public void sendBreak(final int millis){

     if (this.fd != -1) {
		sendBreakNC(this.fd, millis);
	}

  }

  /**

     Sets the flow control mode.

     Parameters:
       flow - control Can be a bitmask combination of
         FLOWCONTROL_NONE: no flow control
         FLOWCONTROL_RTSCTS_IN: RTS/CTS (hardware) flow control for input
         FLOWCONTROL_RTSCTS_OUT: RTS/CTS (hardware) flow control for output
         FLOWCONTROL_XONXOFF_IN: XON/XOFF (software) flow control for input
         FLOWCONTROL_XONXOFF_OUT: XON/XOFF (software) flow control for output
       Throws: UnsupportedCommOperationException
         if any of the flow control mode was not supported by the underline OS,
       or
         if input and output flow control are set to different values, i.e. one
           hardware and one software. The flow control mode will revert to the
           value before the call was made.
  */
  public void setFlowControlMode(final int flowctrl)
         throws UnsupportedCommOperationException
  {
    // Check for invalid combinations.
    if((this.fd == -1) ||
	/* Now FLOWCONTROL_NONE is 0 instead of 1, and hence no need for this
 	   check below!!! */
	/**************************
       (((flowctrl & FLOWCONTROL_NONE) != 0) &&
	(((flowctrl & FLOWCONTROL_RTSCTS_IN) != 0) ||
	 ((flowctrl & FLOWCONTROL_RTSCTS_OUT) != 0) ||
	 ((flowctrl & FLOWCONTROL_XONXOFF_IN) != 0) ||
	 ((flowctrl & FLOWCONTROL_XONXOFF_OUT) != 0))) ||
	 **************************/
       (((flowctrl & FLOWCONTROL_RTSCTS_IN) != 0) &&
	((flowctrl & FLOWCONTROL_XONXOFF_OUT) != 0)) ||
       (((flowctrl & FLOWCONTROL_XONXOFF_IN) != 0) &&
	((flowctrl & FLOWCONTROL_RTSCTS_OUT) != 0)) ||
       (((flowctrl & FLOWCONTROL_RTSCTS_IN) != 0) &&
	((flowctrl & FLOWCONTROL_XONXOFF_IN) != 0)) ||
       (((flowctrl & FLOWCONTROL_RTSCTS_OUT) != 0) &&
	((flowctrl & FLOWCONTROL_XONXOFF_OUT) != 0)))
    {
      throw new UnsupportedCommOperationException();
    }
    else {
      // retcode of -1 is a problem
      if( setFlowControlModeNC( this.fd, flowctrl) != -1 ) {
		this.flowcontrol = flowctrl;
	} else {
		throw new UnsupportedCommOperationException();
	}
    }
    // System.out.println("setFlowControl: flowcontrol = "+flowcontrol+", flowctrl= "+flowctrl);
  }

  /**
     Gets the currently configured flow control mode.

     Returns:
       an integer bitmask of the modes FLOWCONTROL_NONE,
                                       FLOWCONTROL_RTSCTS_IN,
                                       FLOWCONTROL_RTSCTS_OUT,
                                       FLOWCONTROL_XONXOFF_IN, and
                                       FLOWCONTROL_XONXOFF_OUT.
  */
  public int getFlowControlMode()
  {
    if( this.fd > -1 )
      {
        final int retCode = getFlowControlModeNC(this.fd);
        if( retCode == -1 )
        {
          return this.flowcontrol;
        } else
        {
	  if (retCode == 0) {
		this.flowcontrol = FLOWCONTROL_NONE;
	} else {
	     int fl = 0;

	     if ((retCode & 1) != 0) {
			fl |= FLOWCONTROL_RTSCTS_IN;
		}
	     if ((retCode & 2) != 0) {
			fl |= FLOWCONTROL_RTSCTS_OUT;
		}
	     if ((retCode & 4) != 0) {
			fl |= FLOWCONTROL_XONXOFF_IN;
		}
	     if ((retCode & 8) != 0) {
			fl |= FLOWCONTROL_XONXOFF_OUT;
		}

	     this.flowcontrol = fl;
	  }
        }
      } else
      {
        return this.flowcontrol;
      }
    return this.flowcontrol;
  }



  public void setRcvFifoTrigger(final int trigger){}	// Deprecated

  public void setSerialPortParams(final int bd, final int db, final int sb, final int par)
         throws UnsupportedCommOperationException{
     // Validate the values.

     if (this.fd == -1) {
	throw new UnsupportedCommOperationException();
     }

     if ( (db != DATABITS_5) && (db != DATABITS_6) &&
	      (db != DATABITS_7) &&	(db != DATABITS_8) ) {
	throw new UnsupportedCommOperationException();
     }

     if ( (sb != STOPBITS_1) && (sb != STOPBITS_2) && (sb != STOPBITS_1_5) ) {	// 1.5 not supported
	throw new UnsupportedCommOperationException();
     }

     if ( (par != PARITY_NONE) && (par != PARITY_ODD) && (par != PARITY_EVEN) &&
	      (par != PARITY_MARK) && (par != PARITY_SPACE) ) {
	throw new UnsupportedCommOperationException();
     }

     // Now set the desired communication characteristics.
     if (setSerialPortParamsNC(this.fd, bd, db, sb, par) < 0) {
	throw new UnsupportedCommOperationException();
     }

  }

  public void setDTR(final boolean dtr)
  {
    setDTRNC( dtr );
  }

  public boolean isDTR()
  {
    return isDTRNC();
  }

  public void setRTS(final boolean rts)
  {
    setRTSNC( rts );
  }

  public boolean isRTS()
  {
    return isRTSNC();
  }

  public boolean isCTS()
  {
    return isCTSNC();
  }

  public boolean isDSR()
  {
    return isDSRNC();
  }

  public boolean isRI()
  {
    return isRINC();
  }

  public boolean isCD()
  {
    return isCDNC();
  }

  public synchronized void notifyOnDataAvailable(final boolean notify)
  {
    if( notify )
    {
      if( !this.notifyOnDataFlag )
      {
        // instantiate SerialDataEventThread
        if( this.dataThread == null )
        {
          this.dataThread = new SerialDataEventThread(this.fd, this);
          // dataThread.setDaemon( true ); // check it out
          this.dataThread.start();
        }
        this.notifyOnDataFlag = true;
      }
    } else
    {
      if( this.notifyOnDataFlag )
      {
        // Stop SerialDataEventThread
        if( this.dataThread != null ) {
			this.dataThread.setStopThreadFlag(1);
		}
//          stopThread( dataThread );
        this.notifyOnDataFlag = false;
        this.dataThread = null;
      }
    }
  }

  public synchronized void notifyOnOutputEmpty(final boolean notify)
  {
    this.notifyOnBufferFlag = notify;
  }

  public synchronized void notifyOnCTS(final boolean notify)
  {
    if (notify && this.notifyOnCTSFlag) {
		return;	// already enabled
	}
    if( notify &&
        !this.notifyOnCTSFlag )
    {
      if( this.statusThread == null )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        // statusThread.setDaemon( true ); // check it out
        this.statusThread.start();
      }
      this.notifyOnCTSFlag = true;
    } else
    {
      // check all other related flags, all must be false
      if( !this.notifyOnDSRFlag &&
          !this.notifyOnRIFlag  &&
          !this.notifyOnCDFlag  &&
          !this.notifyOnORFlag  &&
          !this.notifyOnPEFlag  &&
          !this.notifyOnFEFlag  &&
          !this.notifyOnBIFlag )
      {
        if( this.statusThread != null )
        {
		  this.statusThread.setStopThreadFlag(1);	
//          stopThread( statusThread );
          this.statusThread = null;
        }
      }
        this.notifyOnCTSFlag = false;
    }
  }

  public synchronized void notifyOnDSR(final boolean notify)
  {
    if (notify && this.notifyOnDSRFlag) {
		return;	// already enabled
	}
    if( notify &&
        !this.notifyOnDSRFlag )
    {
      if( this.statusThread == null )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        this.statusThread.start();
      }
      this.notifyOnDSRFlag = true;
    } else
    {
      // check all other related flags, all must be false
      if( !this.notifyOnCTSFlag &&
          !this.notifyOnRIFlag  &&
          !this.notifyOnCDFlag  &&
          !this.notifyOnORFlag  &&
          !this.notifyOnPEFlag  &&
          !this.notifyOnFEFlag  &&
          !this.notifyOnBIFlag )
      {
        if( this.statusThread != null )
        {
		  this.statusThread.setStopThreadFlag(1);        	
          //stopThread( statusThread );
          this.statusThread = null;
        }
      }
      this.notifyOnDSRFlag = false;
    }
  }

  public synchronized void notifyOnRingIndicator(final boolean notify)
  {
    if (notify && this.notifyOnRIFlag) {
		return;	// already enabled
	}
    if( notify &&
        !this.notifyOnRIFlag )
    {
      if( this.statusThread == null )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        // statusThread.setDaemon( true ); // check it out
        this.statusThread.start();
      }
      this.notifyOnRIFlag = true;
    } else
    {
      // check all other related flags, all must be false
      if( !this.notifyOnCTSFlag &&
          !this.notifyOnDSRFlag &&
          !this.notifyOnCDFlag  &&
          !this.notifyOnORFlag  &&
          !this.notifyOnPEFlag  &&
          !this.notifyOnFEFlag  &&
          !this.notifyOnBIFlag )
      {
        if( this.statusThread != null )
        {
			this.statusThread.setStopThreadFlag(1);        	
			//stopThread( statusThread );
          this.statusThread = null;
        }
      }
      this.notifyOnRIFlag = false;
    }
  }


  public synchronized void notifyOnCarrierDetect(final boolean notify)
  {
    if (notify && this.notifyOnCDFlag) {
		return;	// already enabled
	}
    if( notify &&
        !this.notifyOnCDFlag )
    {
      // instantiate SerialStatusEventThread
      if( this.statusThread == null )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        // statusThread.setDaemon( true ); // check it out
        this.statusThread.start();
      }
      this.notifyOnCDFlag = true;
    } else
    {
      // check all other related flags, all must be false
      if( !this.notifyOnCTSFlag &&
          !this.notifyOnDSRFlag &&
          !this.notifyOnORFlag  &&
          !this.notifyOnRIFlag  &&
          !this.notifyOnPEFlag  &&
          !this.notifyOnFEFlag  &&
          !this.notifyOnBIFlag )
      {
        if( this.statusThread != null )
        {
			this.statusThread.setStopThreadFlag(1);        	
			//stopThread( statusThread );
          this.statusThread = null;
        }
      }
      this.notifyOnCDFlag = false;
    }
  }


  public synchronized void notifyOnOverrunError(final boolean notify)
  {
    if (notify && this.notifyOnORFlag) {
		return;	// already enabled
	}
    if( notify &&
        !this.notifyOnORFlag )
    {
      // instantiate SerialStatusEventThread
      if( this.statusThread == null )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        // statusThread.setDaemon( true ); // check it out
        this.statusThread.start();
      }
      this.notifyOnORFlag = true;
    } else
    {
      // check all other related flags, all must be false
      if( !this.notifyOnCTSFlag &&
          !this.notifyOnDSRFlag &&
          !this.notifyOnRIFlag  &&
          !this.notifyOnCDFlag  &&
          !this.notifyOnPEFlag  &&
          !this.notifyOnFEFlag  &&
          !this.notifyOnBIFlag )
      {
        if( this.statusThread != null )
        {
			this.statusThread.setStopThreadFlag(1);        	
			//stopThread( statusThread );
          this.statusThread = null;
        }
      }
      this.notifyOnORFlag = false;
    }
  }

  public synchronized void notifyOnParityError(final boolean notify)
  {
    if (notify && this.notifyOnPEFlag) {
		return;	// already enabled
	}
    if( notify &&
        !this.notifyOnPEFlag )
    {
      // instantiate SerialStatusEventThread
      if( this.statusThread == null )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        // statusThread.setDaemon( true ); // check it out
        this.statusThread.start();
      }
      this.notifyOnPEFlag = true;
    } else
    {
      // check all other related flags, all must be false
      if( !this.notifyOnCTSFlag &&
          !this.notifyOnDSRFlag &&
          !this.notifyOnRIFlag  &&
          !this.notifyOnCDFlag  &&
          !this.notifyOnORFlag  &&
          !this.notifyOnFEFlag  &&
          !this.notifyOnBIFlag )
      {
        if( this.statusThread != null )
        {
			this.statusThread.setStopThreadFlag(1);        	
			//stopThread( statusThread );
          this.statusThread = null;
        }
      }
      this.notifyOnPEFlag = false;
    }
  }

  public synchronized void notifyOnFramingError(final boolean notify)
  {
    if (notify && this.notifyOnFEFlag) {
		return;	// already enabled
	}
    if( notify &&
        !this.notifyOnFEFlag )
    {
      if( this.statusThread == null )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        // statusThread.setDaemon( true ); // check it out
        this.statusThread.start();
      }
      this.notifyOnFEFlag = true;
    } else
    {
      // check all other related flags, all must be false
      if( !this.notifyOnCTSFlag &&
          !this.notifyOnDSRFlag &&
          !this.notifyOnRIFlag  &&
          !this.notifyOnCDFlag  &&
          !this.notifyOnORFlag  &&
          !this.notifyOnPEFlag  &&
          !this.notifyOnBIFlag )
      {
        if( this.statusThread != null )
        {
			this.statusThread.setStopThreadFlag(1);        	
			//stopThread( statusThread );
          this.statusThread = null;
        }
      }
      this.notifyOnFEFlag = false;
    }
  }


  public synchronized void notifyOnBreakInterrupt(final boolean notify)
  {
    if (notify && this.notifyOnBIFlag) {
		return;	// already enabled
	}
    if( notify &&
        !this.notifyOnBIFlag )
    {
      // instantiate SerialStatusEventThread
      if( this.statusThread == null )
      {
        this.statusThread = new SerialStatusEventThread(this.fd, this);
        // statusThread.setDaemon( true ); // check it out
        this.statusThread.start();
      }
      this.notifyOnBIFlag = true;
    } else
    {
      // check all other related flags, all must be false
      if( !this.notifyOnCTSFlag &&
          !this.notifyOnDSRFlag &&
          !this.notifyOnRIFlag  &&
          !this.notifyOnCDFlag  &&
          !this.notifyOnORFlag  &&
          !this.notifyOnPEFlag  &&
          !this.notifyOnFEFlag )
      {
        if( this.statusThread != null )
        {
			this.statusThread.setStopThreadFlag(1);        	
			//stopThread( statusThread );
          this.statusThread = null;
        }
      }
      this.notifyOnBIFlag = false;
    }
  }


  private native int openDeviceNC(String deviceName, int semID);
  private native int closeDeviceNC(int fd, int semID);

  private native int setFlowControlModeNC( int fd, int flowctrl );
  private native int getFlowControlModeNC( int fd );

/*
  private synchronized void stopThread(Thread th)
  {
     final int  maxcounter =    30;
     int        counter =       0;
     boolean    done =          false;

     while (!done && counter < maxcounter) {

        // update counter and  Interrupt the thread every 5 seconds.
        if ((counter++ % 5) == 0) {

           th.interrupt();

        }

        if (!th.isAlive())
           done = true;
        else {

           try {

              Thread.sleep(1000);       // sleep for a second

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
  private native int sendBreakNC(int fd, int millis);

  synchronized void reportSerialEvent(final int eventType,
                                      final boolean oldvalue,
                                      final boolean newvalue)
  {
     if (this.listener != null)
     {
        final SerialPortEvent se = new SerialPortEvent(this,
                                                 eventType,
                                                 oldvalue,
                                                 newvalue);
        if (se != null) {
			this.listener.serialEvent(se);
		}
     }
  }

  private native int getBaudRateNC( int fd );
  private native int getDataBitsNC( int fd );
  private native int getStopBitsNC( int fd );
  private native int getParityNC( int fd );

  private native void setDTRNC(boolean dtr);
  private native void setRTSNC(boolean rts);
  private native int setSerialPortParamsNC(int fd, int bd, int db, int sb, int par);

  private native boolean isDTRNC();
  private native boolean isRTSNC();
  private native boolean isCTSNC();
  private native boolean isDSRNC();
  private native boolean isRINC();
  private native boolean isCDNC();

}


